/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.archive;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;

public final class ArchiveInspector {
    public ArchiveInspection inspect(Path archive) throws IOException {
        String format = ArchivePolicy.archiveFormat(archive.getFileName().toString());
        ArchiveInspection inspection = switch (format) {
            case "zip" -> inspectZip(archive);
            case "tar" -> inspectTar(archive, false);
            case "tar.gz" -> inspectTar(archive, true);
            case "7z" -> inspectSevenZip(archive);
            case "rar" -> unsupportedRar();
            default -> throw new IOException("不支持的压缩包格式");
        };
        enforceGlobalLimits(inspection);
        return inspection;
    }

    private static ArchiveInspection inspectZip(Path archive) throws IOException {
        ArchiveInspection.Builder result = new ArchiveInspection.Builder("zip");
        try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntriesInPhysicalOrder();
            int index = 0;
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                index++;
                if (index > ArchivePolicy.MAX_ENTRIES) {
                    throw new IOException("压缩包文件数超过" + ArchivePolicy.MAX_ENTRIES + "项安全上限");
                }
                boolean safePath = ArchivePolicy.isSafeRelativePath(entry.getName());
                boolean symlink = entry.isUnixSymlink();
                boolean readable = zip.canReadEntryData(entry) && !symlink;
                ArchiveCategory category = category(entry.getName(), entry.isDirectory(), safePath, readable, symlink);
                String reason = reason(category, safePath, readable, symlink);
                result.add(new ArchiveEntryInfo(index, safeName(entry.getName()), entry.isDirectory(),
                        normalizedSize(entry.getSize()), normalizedSize(entry.getCompressedSize()), category,
                        reason, safePath, readable));
                checkEntryLimits(result, index, entry.getSize(), entry.getCompressedSize());
            }
        }
        return result.build();
    }

    private static ArchiveInspection inspectTar(Path archive, boolean gzip) throws IOException {
        ArchiveInspection.Builder result = new ArchiveInspection.Builder(gzip ? "tar.gz" : "tar");
        try (InputStream fileInput = Files.newInputStream(archive);
             InputStream decoded = gzip ? new GzipCompressorInputStream(fileInput) : fileInput;
             LimitedInputStream limited = new LimitedInputStream(decoded,
                     ArchivePolicy.MAX_TOTAL_DECLARED_BYTES + 128L * 1024 * 1024);
             TarArchiveInputStream tar = new TarArchiveInputStream(limited)) {
            int index = 0;
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                index++;
                if (index > ArchivePolicy.MAX_ENTRIES) {
                    throw new IOException("压缩包文件数超过" + ArchivePolicy.MAX_ENTRIES + "项安全上限");
                }
                boolean safePath = ArchivePolicy.isSafeRelativePath(entry.getName());
                boolean link = entry.isLink() || entry.isSymbolicLink();
                boolean readable = tar.canReadEntryData(entry) && !link;
                ArchiveCategory category = category(entry.getName(), entry.isDirectory(), safePath, readable, link);
                String reason = reason(category, safePath, readable, link);
                result.add(new ArchiveEntryInfo(index, safeName(entry.getName()), entry.isDirectory(),
                        normalizedSize(entry.getSize()), -1, category, reason, safePath, readable));
                checkEntryLimits(result, index, entry.getSize(), -1);
            }
        }
        return result.build();
    }

    private static ArchiveInspection inspectSevenZip(Path archive) throws IOException {
        ArchiveInspection.Builder result = new ArchiveInspection.Builder("7z");
        try (SevenZFile sevenZip = SevenZFile.builder().setPath(archive).get()) {
            int index = 0;
            for (SevenZArchiveEntry entry : sevenZip.getEntries()) {
                index++;
                if (index > ArchivePolicy.MAX_ENTRIES) {
                    throw new IOException("压缩包文件数超过" + ArchivePolicy.MAX_ENTRIES + "项安全上限");
                }
                boolean safePath = ArchivePolicy.isSafeRelativePath(entry.getName());
                boolean readable = entry.hasStream() || entry.isDirectory();
                ArchiveCategory category = category(entry.getName(), entry.isDirectory(), safePath, readable, false);
                result.add(new ArchiveEntryInfo(index, safeName(entry.getName()), entry.isDirectory(),
                        normalizedSize(entry.getSize()), -1, category,
                        reason(category, safePath, readable, false), safePath, readable));
                checkEntryLimits(result, index, entry.getSize(), -1);
            }
        }
        return result.build();
    }

    private static ArchiveInspection unsupportedRar() {
        ArchiveInspection.Builder result = new ArchiveInspection.Builder("rar");
        result.reject("已识别RAR压缩包，但第一阶段不解压或处理；请转换为ZIP、7Z、TAR或TAR.GZ后重试。");
        return result.build();
    }

    private static ArchiveCategory category(String name, boolean directory, boolean safePath,
            boolean readable, boolean link) {
        if (directory) {
            return ArchiveCategory.UNSUPPORTED;
        }
        if (!safePath || link) {
            return ArchiveCategory.DANGEROUS;
        }
        if (!readable) {
            return ArchiveCategory.UNSUPPORTED;
        }
        return ArchivePolicy.classify(name);
    }

    private static String reason(ArchiveCategory category, boolean safePath, boolean readable, boolean link) {
        if (!safePath) {
            return "路径不安全，可能逃逸压缩包工作目录";
        }
        if (link) {
            return "符号链接或硬链接不允许解压";
        }
        if (!readable) {
            return "条目可能已加密或使用当前运行时不支持的压缩算法";
        }
        return ArchivePolicy.reason(category);
    }

    private static void checkEntryLimits(ArchiveInspection.Builder result, int index, long size, long compressedSize) {
        if (index > ArchivePolicy.MAX_ENTRIES) {
            result.reject("压缩包文件数超过" + ArchivePolicy.MAX_ENTRIES + "项安全上限");
        }
        if (size > ArchivePolicy.MAX_ENTRY_BYTES) {
            result.reject("存在展开后超过1GB的单个条目");
        }
        if (size > 0 && compressedSize > 0 && exceedsCompressionRatio(size, compressedSize)) {
            result.reject("存在压缩倍率超过" + ArchivePolicy.MAX_COMPRESSION_RATIO + "倍的可疑条目");
        }
    }

    private static boolean exceedsCompressionRatio(long size, long compressedSize) {
        long quotient = size / compressedSize;
        return quotient > ArchivePolicy.MAX_COMPRESSION_RATIO
                || (quotient == ArchivePolicy.MAX_COMPRESSION_RATIO && size % compressedSize != 0);
    }

    private static void enforceGlobalLimits(ArchiveInspection inspection) throws IOException {
        if (inspection.entries().size() > ArchivePolicy.MAX_ENTRIES) {
            throw new IOException("压缩包文件数超过安全上限");
        }
        if (inspection.totalDeclaredSize() > ArchivePolicy.MAX_TOTAL_DECLARED_BYTES) {
            throw new IOException("压缩包声明展开总量超过8GB安全上限");
        }
    }

    private static long normalizedSize(long size) {
        return size < 0 ? -1 : size;
    }

    private static String safeName(String name) {
        if (name == null) {
            return "";
        }
        String value = name.replaceAll("[\\r\\n\\t\\p{Cntrl}]", "�");
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
}

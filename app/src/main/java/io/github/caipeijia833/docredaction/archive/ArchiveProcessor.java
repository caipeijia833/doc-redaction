/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.archive;

import io.github.caipeijia833.docredaction.processor.DocumentProcessor;
import io.github.caipeijia833.docredaction.processor.ProcessReport;
import io.github.caipeijia833.docredaction.processor.ProcessorRegistry;
import io.github.caipeijia833.docredaction.processor.ResidualScanner;
import io.github.caipeijia833.docredaction.rules.RuleEngine;
import io.github.caipeijia833.docredaction.rules.FilenameRedactor;
import io.github.caipeijia833.docredaction.util.JsonUtil;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ArchiveProcessor {
    private static final String MANIFEST_NAME = "_doc_redaction_manifest.json";

    private final ProcessorRegistry registry;
    private final RuleEngine ruleEngine;

    public ArchiveProcessor(ProcessorRegistry registry, RuleEngine ruleEngine) {
        this.registry = registry;
        this.ruleEngine = ruleEngine;
    }

    public ProcessReport process(Path input, Path output, Path workDirectory, boolean includeUnprocessed)
            throws Exception {
        return process(input, output, workDirectory, includeUnprocessed, Map.of());
    }

    public ProcessReport process(Path input, Path output, Path workDirectory, boolean includeUnprocessed,
            Map<Integer, ArchiveEntryAction> archiveEntryActions) throws Exception {
        Files.createDirectories(workDirectory);
        ArchiveInspection currentInspection = new ArchiveInspector().inspect(input);
        if (currentInspection.rejected()) {
            String reason = currentInspection.warnings().isEmpty()
                    ? "压缩包未通过安全预检" : currentInspection.warnings().getFirst();
            throw new IOException(reason);
        }
        String format = ArchivePolicy.archiveFormat(input.getFileName().toString());
        Map<Integer, ArchiveEntryAction> decisions = archiveEntryActions == null
                ? Map.of() : Map.copyOf(archiveEntryActions);
        return switch (format) {
            case "zip" -> processZip(input, output, workDirectory, includeUnprocessed, decisions);
            case "tar" -> processTar(input, output, workDirectory, includeUnprocessed, decisions, false);
            case "tar.gz" -> processTar(input, output, workDirectory, includeUnprocessed, decisions, true);
            case "7z" -> processSevenZip(input, output, workDirectory, includeUnprocessed, decisions);
            default -> throw new IOException("当前不能处理该压缩包格式");
        };
    }

    private ProcessReport processZip(Path input, Path output, Path work, boolean includeUnprocessed,
            Map<Integer, ArchiveEntryAction> decisions)
            throws Exception {
        ProcessReport report = new ProcessReport();
        List<ManifestItem> manifest = new ArrayList<>();
        Set<String> usedPaths = initialUsedPaths();
        ExpandedDataLimiter expanded = new ExpandedDataLimiter(ArchivePolicy.MAX_TOTAL_DECLARED_BYTES);
        try (ZipFile source = ZipFile.builder().setPath(input).get();
             ZipArchiveOutputStream target = new ZipArchiveOutputStream(output.toFile())) {
            target.setUseZip64(org.apache.commons.compress.archivers.zip.Zip64Mode.AsNeeded);
            Enumeration<ZipArchiveEntry> entries = source.getEntriesInPhysicalOrder();
            int index = 0;
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                index++;
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                boolean readable = source.canReadEntryData(entry) && !entry.isUnixSymlink();
                ArchiveCategory category = readable ? ArchivePolicy.classify(entryName) : ArchiveCategory.DANGEROUS;
                try (InputStream content = readable ? source.getInputStream(entry) : InputStream.nullInputStream()) {
                    handleEntry(index, entryName, entry.getSize(), readable, category, content,
                            work, includeUnprocessed, decisions, report, manifest, usedPaths, expanded,
                            (name, path) -> addZipFile(target, name, path));
                }
            }
            addZipBytes(target, MANIFEST_NAME, manifestJson("zip", includeUnprocessed, manifest, report));
        }
        finishReport(report, manifest, includeUnprocessed);
        return report;
    }

    private ProcessReport processTar(Path input, Path output, Path work, boolean includeUnprocessed,
            Map<Integer, ArchiveEntryAction> decisions, boolean gzip)
            throws Exception {
        ProcessReport report = new ProcessReport();
        List<ManifestItem> manifest = new ArrayList<>();
        Set<String> usedPaths = initialUsedPaths();
        ExpandedDataLimiter expanded = new ExpandedDataLimiter(ArchivePolicy.MAX_TOTAL_DECLARED_BYTES);
        try (InputStream rawInput = Files.newInputStream(input);
             InputStream decodedInput = gzip ? new GzipCompressorInputStream(rawInput) : rawInput;
             LimitedInputStream limitedInput = new LimitedInputStream(decodedInput,
                     ArchivePolicy.MAX_TOTAL_DECLARED_BYTES + 128L * 1024 * 1024);
             TarArchiveInputStream source = new TarArchiveInputStream(limitedInput);
             OutputStream rawOutput = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW);
             OutputStream encodedOutput = gzip ? new GzipCompressorOutputStream(rawOutput) : rawOutput;
             TarArchiveOutputStream target = new TarArchiveOutputStream(encodedOutput)) {
            target.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            target.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            int index = 0;
            TarArchiveEntry entry;
            while ((entry = source.getNextEntry()) != null) {
                index++;
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                boolean link = entry.isLink() || entry.isSymbolicLink();
                boolean readable = source.canReadEntryData(entry) && !link;
                ArchiveCategory category = readable ? ArchivePolicy.classify(entryName) : ArchiveCategory.DANGEROUS;
                handleEntry(index, entryName, entry.getSize(), readable, category, source,
                        work, includeUnprocessed, decisions, report, manifest, usedPaths, expanded,
                        (name, path) -> addTarFile(target, name, path));
            }
            addTarBytes(target, MANIFEST_NAME, manifestJson(gzip ? "tar.gz" : "tar",
                    includeUnprocessed, manifest, report));
            target.finish();
        }
        finishReport(report, manifest, includeUnprocessed);
        return report;
    }

    private ProcessReport processSevenZip(Path input, Path output, Path work, boolean includeUnprocessed,
            Map<Integer, ArchiveEntryAction> decisions)
            throws Exception {
        ProcessReport report = new ProcessReport();
        List<ManifestItem> manifest = new ArrayList<>();
        Set<String> usedPaths = initialUsedPaths();
        ExpandedDataLimiter expanded = new ExpandedDataLimiter(ArchivePolicy.MAX_TOTAL_DECLARED_BYTES);
        try (SevenZFile source = SevenZFile.builder().setPath(input).get();
             SevenZOutputFile target = new SevenZOutputFile(output.toFile())) {
            int index = 0;
            for (SevenZArchiveEntry entry : source.getEntries()) {
                index++;
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                boolean readable = entry.hasStream();
                ArchiveCategory category = readable ? ArchivePolicy.classify(entryName) : ArchiveCategory.DANGEROUS;
                try (InputStream content = readable ? source.getInputStream(entry) : InputStream.nullInputStream()) {
                    handleEntry(index, entryName, entry.getSize(), readable, category, content,
                            work, includeUnprocessed, decisions, report, manifest, usedPaths, expanded,
                            (name, path) -> addSevenZipFile(target, name, path));
                }
            }
            Path manifestFile = work.resolve("archive-manifest.json");
            Files.writeString(manifestFile, manifestJson("7z", includeUnprocessed, manifest, report),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            addSevenZipFile(target, MANIFEST_NAME, manifestFile);
            target.finish();
            Files.deleteIfExists(manifestFile);
        }
        finishReport(report, manifest, includeUnprocessed);
        return report;
    }

    private void handleEntry(int index, String entryName, long declaredSize, boolean readable,
            ArchiveCategory category, InputStream content, Path work, boolean includeUnprocessed,
            Map<Integer, ArchiveEntryAction> decisions,
            ProcessReport aggregate, List<ManifestItem> manifest, Set<String> usedPaths,
            ExpandedDataLimiter expanded, EntrySink sink) throws Exception {
        Path entryRoot = work.resolve(".archive-entry-path-root").toAbsolutePath().normalize();
        Path resolvedEntry = null;
        boolean declaredSafe = ArchivePolicy.isSafeRelativePath(entryName);
        if (declaredSafe) {
            try {
                resolvedEntry = entryRoot.resolve(entryName.replace('\\', '/')).normalize();
            } catch (RuntimeException ignored) {
                // Invalid path syntax is treated as unsafe and is never written to an output archive.
            }
        }
        boolean safe = resolvedEntry != null && resolvedEntry.startsWith(entryRoot);
        String normalizedName = safe ? relativeArchivePath(entryRoot, resolvedEntry) : displayEntryName(entryName);
        if (!safe || !readable || category == ArchiveCategory.DANGEROUS) {
            manifest.add(new ManifestItem(normalizedName, null, category.name(), "excluded",
                    safe ? "条目不可读取或属于高风险类型" : "路径不安全"));
            return;
        }
        if (declaredSize > ArchivePolicy.MAX_ENTRY_BYTES) {
            manifest.add(new ManifestItem(normalizedName, null, category.name(), "excluded", "单个条目超过1GB上限"));
            return;
        }
        ArchiveEntryAction action = decisions.get(index);
        if (action == null) {
            action = category == ArchiveCategory.REDACTABLE ? ArchiveEntryAction.REDACT
                    : includeUnprocessed ? ArchiveEntryAction.KEEP_UNPROCESSED : ArchiveEntryAction.EXCLUDE;
        }
        if (category == ArchiveCategory.REDACTABLE && action == ArchiveEntryAction.KEEP_UNPROCESSED) {
            throw new IOException("可脱敏条目不能绕过处理后原样保留");
        }
        if (category != ArchiveCategory.REDACTABLE && action == ArchiveEntryAction.REDACT) {
            throw new IOException("不可脱敏条目不能选择脱敏处理");
        }
        if (action == ArchiveEntryAction.EXCLUDE) {
            manifest.add(new ManifestItem(normalizedName, null, category.name(), "excluded",
                    decisions.containsKey(index) ? "用户已确认排除" : ArchivePolicy.reason(category)));
            return;
        }
        if (category == ArchiveCategory.REDACTABLE) {
            String extension = ArchivePolicy.extension(normalizedName);
            Path extracted = work.resolve("entry-" + index + "." + extension);
            Path processed = work.resolve("entry-" + index + "-redacted." + extension);
            try {
                copyBounded(content, extracted, ArchivePolicy.MAX_ENTRY_BYTES, expanded);
                DocumentProcessor processor = registry.requireProcessor(extracted);
                ProcessReport itemReport = processor.process(extracted, processed, ruleEngine);
                ResidualScanner.verify(extracted, processed, ruleEngine, itemReport);
                aggregate.merge(itemReport);
                String outputName = safeOutputArchivePath(work, allocateArchivePath(normalizedName, usedPaths));
                sink.add(outputName, processed);
                manifest.add(new ManifestItem(normalizedName, outputName, category.name(), "redacted", null));
            } catch (ExpandedDataLimiter.LimitExceededException ex) {
                throw ex;
            } catch (Exception ex) {
                manifest.add(new ManifestItem(normalizedName, null, category.name(), "failed", safeMessage(ex)));
                aggregate.warning("压缩包内文件处理失败：" + abbreviated(normalizedName));
            } finally {
                Files.deleteIfExists(extracted);
                Files.deleteIfExists(processed);
            }
            return;
        }
        if (action == ArchiveEntryAction.KEEP_UNPROCESSED) {
            Path unchanged = work.resolve("entry-" + index + ".unchanged");
            try {
                copyBounded(content, unchanged, ArchivePolicy.MAX_ENTRY_BYTES, expanded);
                String outputName = safeOutputArchivePath(work, allocateArchivePath(normalizedName, usedPaths));
                sink.add(outputName, unchanged);
                manifest.add(new ManifestItem(normalizedName, outputName, category.name(), "included_unprocessed",
                        "用户已确认原样保留；未执行敏感信息检查"));
            } finally {
                Files.deleteIfExists(unchanged);
            }
        }
    }

    private String allocateArchivePath(String originalPath, Set<String> usedPaths) {
        String redacted = FilenameRedactor.redactArchivePath(originalPath, ruleEngine);
        return FilenameRedactor.allocateUniquePath(redacted, usedPaths);
    }

    /**
     * Re-validates the final redacted path immediately before it is written to an
     * output archive. The normalized path must remain within a trusted virtual
     * archive root, preventing path traversal through either source names or a
     * future filename-redaction implementation.
     */
    private static String safeOutputArchivePath(Path work, String candidateName) throws IOException {
        Path outputRoot = work.resolve(".archive-output-path-root").toAbsolutePath().normalize();
        Path candidate;
        try {
            candidate = outputRoot.resolve(candidateName).normalize();
        } catch (RuntimeException ex) {
            throw new IOException("Output archive path is invalid", ex);
        }
        if (!candidate.startsWith(outputRoot)) {
            throw new IOException("Output archive path escapes its root");
        }
        String relative = relativeArchivePath(outputRoot, candidate);
        if (!ArchivePolicy.isSafeRelativePath(relative)) {
            throw new IOException("Output archive path is unsafe");
        }
        return relative;
    }

    private static String relativeArchivePath(Path root, Path candidate) {
        return root.relativize(candidate).toString().replace('\\', '/');
    }

    private static String displayEntryName(String entryName) {
        return entryName == null ? "(missing entry name)" : entryName.replace('\0', '?').replace('\\', '/');
    }

    private static Set<String> initialUsedPaths() {
        Set<String> used = new HashSet<>();
        used.add(MANIFEST_NAME.toLowerCase(java.util.Locale.ROOT));
        return used;
    }

    private static void copyBounded(InputStream input, Path target, long limit, ExpandedDataLimiter expanded)
            throws IOException {
        long total = 0;
        byte[] buffer = new byte[1024 * 1024];
        try (OutputStream output = Files.newOutputStream(target,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > limit) {
                    throw new IOException("压缩包条目展开后超过1GB上限");
                }
                expanded.record(read);
                output.write(buffer, 0, read);
            }
        }
    }

    private static void addZipFile(ZipArchiveOutputStream output, String name, Path file) throws IOException {
        ZipArchiveEntry entry = new ZipArchiveEntry(name);
        entry.setSize(Files.size(file));
        output.putArchiveEntry(entry);
        Files.copy(file, output);
        output.closeArchiveEntry();
    }

    private static void addZipBytes(ZipArchiveOutputStream output, String name, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ZipArchiveEntry entry = new ZipArchiveEntry(name);
        entry.setSize(bytes.length);
        output.putArchiveEntry(entry);
        output.write(bytes);
        output.closeArchiveEntry();
    }

    private static void addTarFile(TarArchiveOutputStream output, String name, Path file) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(Files.size(file));
        entry.setModTime(0);
        output.putArchiveEntry(entry);
        Files.copy(file, output);
        output.closeArchiveEntry();
    }

    private static void addTarBytes(TarArchiveOutputStream output, String name, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(bytes.length);
        entry.setModTime(0);
        output.putArchiveEntry(entry);
        output.write(bytes);
        output.closeArchiveEntry();
    }

    private static void addSevenZipFile(SevenZOutputFile output, String name, Path file) throws IOException {
        SevenZArchiveEntry entry = output.createArchiveEntry(file, name);
        output.putArchiveEntry(entry);
        output.write(file);
        output.closeArchiveEntry();
    }

    private static String manifestJson(String format, boolean includeUnprocessed,
            List<ManifestItem> items, ProcessReport report) {
        StringBuilder entries = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                entries.append(',');
            }
            entries.append(items.get(i).toJson());
        }
        entries.append(']');
        return "{" +
                "\"schema\":\"doc-redaction-archive-manifest/v2\"," +
                "\"createdAt\":" + JsonUtil.quote(Instant.now().toString()) + ',' +
                "\"format\":" + JsonUtil.quote(format) + ',' +
                "\"includeUnprocessedConfirmed\":" + includeUnprocessed + ',' +
                "\"totalMatches\":" + report.totalMatches() + ',' +
                "\"entries\":" + entries +
                "}";
    }

    private static void finishReport(ProcessReport report, List<ManifestItem> manifest, boolean includeUnprocessed) {
        long redacted = manifest.stream().filter(item -> item.action().equals("redacted")).count();
        long excluded = manifest.stream().filter(item -> item.action().equals("excluded")).count();
        long unchanged = manifest.stream().filter(item -> item.action().equals("included_unprocessed")).count();
        long failed = manifest.stream().filter(item -> item.action().equals("failed")).count();
        report.warning("压缩包处理汇总：脱敏" + redacted + "项，排除" + excluded + "项，原样保留"
                + unchanged + "项，失败" + failed + "项。");
        if (includeUnprocessed && unchanged > 0) {
            report.warning("结果包包含用户确认原样保留的未检查文件，不能视为全部内容均已脱敏。");
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getClass().getSimpleName();
        }
        message = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return message.length() > 160 ? message.substring(0, 160) : message;
    }

    private static String abbreviated(String value) {
        return value.length() > 80 ? value.substring(0, 80) + "…" : value;
    }

    @FunctionalInterface
    private interface EntrySink {
        void add(String name, Path file) throws IOException;
    }

    private record ManifestItem(String originalPath, String outputPath, String category, String action, String note) {
        String toJson() {
            return "{" +
                    "\"originalPath\":" + JsonUtil.quote(originalPath) + ',' +
                    "\"outputPath\":" + JsonUtil.quote(outputPath) + ',' +
                    "\"category\":" + JsonUtil.quote(category) + ',' +
                    "\"action\":" + JsonUtil.quote(action) + ',' +
                    "\"note\":" + JsonUtil.quote(note) +
                    "}";
        }
    }
}

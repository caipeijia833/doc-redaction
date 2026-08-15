/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.archive;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

public final class ArchivePolicy {
    public static final int MAX_ENTRIES = 10_000;
    public static final long MAX_ENTRY_BYTES = 1024L * 1024 * 1024;
    public static final long MAX_TOTAL_DECLARED_BYTES = 8L * 1024 * 1024 * 1024;
    public static final long MAX_COMPRESSION_RATIO = 200L;

    private static final Set<String> REDACTABLE = Set.of(
            "docx", "xlsx", "pptx", "pdf", "ofd", "png", "jpg", "jpeg", "bmp",
            "mp3", "wav", "m4a", "flac", "mp4", "mov", "mkv");
    private static final Set<String> CONVERTIBLE = Set.of("doc", "xls", "ppt", "wps", "dps", "et", "rtf");
    private static final Set<String> PREVIEW_ONLY = Set.of(
            "gif", "tif", "tiff", "webp",
            "avi", "flv", "wmv");
    private static final Set<String> NESTED_ARCHIVES = Set.of("zip", "7z", "tar", "gz", "tgz", "rar");
    private static final Set<String> DANGEROUS = Set.of(
            "exe", "dll", "com", "scr", "msi", "bat", "cmd", "ps1", "vbs", "vbe", "js", "jse",
            "wsf", "wsh", "hta", "jar", "app", "dmg", "pkg", "sh", "bash", "zsh", "so", "dylib");

    private ArchivePolicy() {
    }

    public static ArchiveCategory classify(String name) {
        String extension = extension(name);
        if (DANGEROUS.contains(extension)) {
            return ArchiveCategory.DANGEROUS;
        }
        if (REDACTABLE.contains(extension)) {
            return ArchiveCategory.REDACTABLE;
        }
        if (CONVERTIBLE.contains(extension)) {
            return ArchiveCategory.CONVERTIBLE;
        }
        if (PREVIEW_ONLY.contains(extension)) {
            return ArchiveCategory.PREVIEW_ONLY;
        }
        if (NESTED_ARCHIVES.contains(extension)) {
            return ArchiveCategory.NESTED_ARCHIVE;
        }
        if (extension.isBlank()) {
            return ArchiveCategory.UNKNOWN;
        }
        return ArchiveCategory.UNSUPPORTED;
    }

    public static String reason(ArchiveCategory category) {
        return switch (category) {
            case REDACTABLE -> "可使用当前格式处理器脱敏";
            case CONVERTIBLE -> "旧版Office/WPS格式需本地转换器，当前不自动转换";
            case PREVIEW_ONLY -> "已识别格式，但当前阶段尚未提供安全脱敏处理或安全重封装";
            case NESTED_ARCHIVE -> "嵌套压缩包默认不递归展开";
            case UNSUPPORTED -> "当前不支持该文件格式";
            case DANGEROUS -> "可执行或脚本类高风险文件，始终排除";
            case UNKNOWN -> "无法根据文件名识别格式";
        };
    }

    public static boolean isSupportedArchiveName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".zip") || lower.endsWith(".7z") || lower.endsWith(".tar")
                || lower.endsWith(".tar.gz") || lower.endsWith(".tgz");
    }

    public static String archiveFormat(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
            return "tar.gz";
        }
        if (lower.endsWith(".7z")) {
            return "7z";
        }
        if (lower.endsWith(".tar")) {
            return "tar";
        }
        if (lower.endsWith(".zip")) {
            return "zip";
        }
        if (lower.endsWith(".rar")) {
            return "rar";
        }
        return "unknown";
    }

    public static boolean isSafeRelativePath(String name) {
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0) {
            return false;
        }
        String normalizedSeparators = name.replace('\\', '/');
        if (normalizedSeparators.startsWith("/") || normalizedSeparators.startsWith("//")
                || normalizedSeparators.matches("^[A-Za-z]:.*") || normalizedSeparators.contains(":")) {
            return false;
        }
        Path normalized;
        try {
            normalized = Path.of(normalizedSeparators).normalize();
        } catch (RuntimeException ex) {
            return false;
        }
        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            return false;
        }
        for (Path part : normalized) {
            String value = part.toString();
            String base = value.contains(".") ? value.substring(0, value.indexOf('.')) : value;
            String upper = base.toUpperCase(Locale.ROOT);
            if (Set.of("CON", "PRN", "AUX", "NUL", "CLOCK$",
                    "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
                    "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9")
                    .contains(upper)) {
                return false;
            }
            if (value.endsWith(".") || value.endsWith(" ")) {
                return false;
            }
        }
        return true;
    }

    public static String extension(String name) {
        if (name == null) {
            return "";
        }
        String value = name.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        int dot = value.lastIndexOf('.');
        return dot > 0 && dot < value.length() - 1 ? value.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}

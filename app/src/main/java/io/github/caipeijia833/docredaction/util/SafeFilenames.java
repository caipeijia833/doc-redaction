/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.util;

import java.util.Locale;
import java.util.Set;

/** Cross-platform leaf-name normalization for Windows and macOS packages. */
public final class SafeFilenames {
    public static final int MAX_LENGTH = 180;
    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL", "CLOCK$",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private SafeFilenames() {
    }

    public static String sanitizeLeafName(String value) {
        String name = value == null ? "upload" : value.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_")
                .replaceAll("[. ]+$", "").trim();
        if (name.isBlank() || name.equals(".") || name.equals("..")) {
            name = "upload";
        }
        NameParts parts = splitName(name);
        if (WINDOWS_RESERVED.contains(parts.stem().toUpperCase(Locale.ROOT))) {
            name = "_" + name;
            parts = splitName(name);
        }
        return trimToLimit(name, parts.extension());
    }

    public static String trimToLimit(String value, String extension) {
        int points = value.codePointCount(0, value.length());
        if (points <= MAX_LENGTH) {
            return value;
        }
        String stem = value.substring(0, value.length() - extension.length());
        int stemLimit = Math.max(1, MAX_LENGTH - extension.codePointCount(0, extension.length()));
        int keep = Math.min(stemLimit, stem.codePointCount(0, stem.length()));
        return stem.substring(0, stem.offsetByCodePoints(0, keep)) + extension;
    }

    private static NameParts splitName(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tar.gz") && value.length() > 7) {
            return new NameParts(value.substring(0, value.length() - 7), value.substring(value.length() - 7));
        }
        int dot = value.lastIndexOf('.');
        return dot <= 0 ? new NameParts(value, "")
                : new NameParts(value.substring(0, dot), value.substring(dot));
    }

    private record NameParts(String stem, String extension) {
    }
}

/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.rules;

import io.github.caipeijia833.docredaction.util.SafeFilenames;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Redacts sensitive values in output names without changing the file type. */
public final class FilenameRedactor {
    private static final String TOKEN = "REDACTED";

    private FilenameRedactor() {
    }

    public static Preview previewOutputName(String originalName, RuleEngine rules) {
        String safeOriginal = safeLeafName(originalName);
        NameParts parts = splitName(safeOriginal);
        String redactedStem = redactComponent(parts.stem(), rules);
        String candidate = trimToLimit(redactedStem + "_脱敏" + parts.extension(), parts.extension());
        return new Preview(safeOriginal, candidate, !safeOriginal.equals(candidate));
    }

    public static String redactArchivePath(String path, RuleEngine rules) {
        String normalized = path == null ? "" : path.replace('\\', '/');
        String[] segments = normalized.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (segments[i].isBlank()) {
                continue;
            }
            if (i == segments.length - 1) {
                NameParts parts = splitName(segments[i]);
                segments[i] = safeLeafName(redactComponent(parts.stem(), rules) + parts.extension());
            } else {
                segments[i] = safeLeafName(redactComponent(segments[i], rules));
            }
        }
        return String.join("/", segments);
    }

    public static List<Preview> allocatePreviews(List<String> originalNames, RuleEngine rules,
            Set<String> existingNames) {
        Set<String> used = new HashSet<>();
        if (existingNames != null) {
            existingNames.stream().filter(value -> value != null && !value.isBlank())
                    .map(FilenameRedactor::collisionKey).forEach(used::add);
        }
        List<Preview> previews = new ArrayList<>();
        if (originalNames == null) {
            return List.of();
        }
        for (String originalName : originalNames) {
            Preview base = previewOutputName(originalName, rules);
            String allocated = allocateUnique(base.redactedName(), used);
            previews.add(new Preview(base.originalName(), allocated,
                    !base.originalName().equals(allocated)));
        }
        return List.copyOf(previews);
    }

    public static String allocateUnique(String candidate, Set<String> usedCollisionKeys) {
        String safeCandidate = safeLeafName(candidate);
        String key = collisionKey(safeCandidate);
        if (usedCollisionKeys.add(key)) {
            return safeCandidate;
        }
        NameParts parts = splitName(safeCandidate);
        for (int index = 2; index < 100_000; index++) {
            String suffix = " (" + index + ")";
            String value = trimToLimit(parts.stem() + suffix + parts.extension(), parts.extension());
            if (usedCollisionKeys.add(collisionKey(value))) {
                return value;
            }
        }
        throw new IllegalStateException("无法为脱敏文件名分配唯一名称");
    }

    public static String allocateUniquePath(String candidate, Set<String> usedCollisionKeys) {
        String normalized = candidate.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String parent = slash < 0 ? "" : normalized.substring(0, slash + 1);
        String leaf = slash < 0 ? normalized : normalized.substring(slash + 1);
        String key = collisionKey(normalized);
        if (usedCollisionKeys.add(key)) {
            return normalized;
        }
        NameParts parts = splitName(leaf);
        for (int index = 2; index < 100_000; index++) {
            String value = parent + trimToLimit(parts.stem() + " (" + index + ")" + parts.extension(),
                    parts.extension());
            if (usedCollisionKeys.add(collisionKey(value))) {
                return value;
            }
        }
        throw new IllegalStateException("无法为压缩包内部文件名分配唯一名称");
    }

    private static String redactComponent(String value, RuleEngine rules) {
        if (value == null || value.isBlank()) {
            return value == null ? "" : value;
        }
        String analysis = value.replace('_', ' ').replace('-', ' ');
        List<SensitiveMatch> matches = rules.detect(analysis);
        if (matches.isEmpty()) {
            return value;
        }
        StringBuilder output = new StringBuilder(value);
        for (int index = matches.size() - 1; index >= 0; index--) {
            SensitiveMatch match = matches.get(index);
            output.replace(match.start(), match.end(), TOKEN);
        }
        return output.toString();
    }

    private static String safeLeafName(String value) {
        return SafeFilenames.sanitizeLeafName(value);
    }

    private static String trimToLimit(String value, String extension) {
        return SafeFilenames.trimToLimit(value, extension);
    }

    private static NameParts splitName(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tar.gz") && value.length() > 7) {
            return new NameParts(value.substring(0, value.length() - 7), value.substring(value.length() - 7));
        }
        int dot = value.lastIndexOf('.');
        if (dot <= 0) {
            return new NameParts(value, "");
        }
        return new NameParts(value.substring(0, dot), value.substring(dot));
    }

    private static String collisionKey(String value) {
        return value.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private record NameParts(String stem, String extension) {
    }

    public record Preview(String originalName, String redactedName, boolean changed) {
    }
}

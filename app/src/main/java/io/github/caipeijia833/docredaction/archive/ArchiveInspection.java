/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.archive;

import io.github.caipeijia833.docredaction.util.JsonUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class ArchiveInspection {
    public static final String INCLUDE_UNPROCESSED_CONFIRMATION = "INCLUDE_UNREVIEWED_FILES";

    private final String format;
    private final List<ArchiveEntryInfo> entries;
    private final List<String> warnings;
    private final long totalDeclaredSize;
    private final boolean rejected;

    public ArchiveInspection(String format, List<ArchiveEntryInfo> entries, List<String> warnings,
            long totalDeclaredSize, boolean rejected) {
        this.format = format;
        this.entries = List.copyOf(entries);
        this.warnings = List.copyOf(warnings);
        this.totalDeclaredSize = totalDeclaredSize;
        this.rejected = rejected;
    }

    public String format() {
        return format;
    }

    public List<ArchiveEntryInfo> entries() {
        return entries;
    }

    public List<String> warnings() {
        return warnings;
    }

    public long totalDeclaredSize() {
        return totalDeclaredSize;
    }

    public boolean rejected() {
        return rejected;
    }

    public int fileCount() {
        return (int) entries.stream().filter(entry -> !entry.directory()).count();
    }

    public int processableCount() {
        return (int) entries.stream().filter(ArchiveEntryInfo::processedByDefault).count();
    }

    public int excludedByDefaultCount() {
        return (int) entries.stream().filter(entry -> !entry.directory() && !entry.processedByDefault()).count();
    }

    public Map<ArchiveCategory, Integer> counts() {
        Map<ArchiveCategory, Integer> counts = new EnumMap<>(ArchiveCategory.class);
        for (ArchiveEntryInfo entry : entries) {
            if (!entry.directory()) {
                counts.merge(entry.category(), 1, Integer::sum);
            }
        }
        return Collections.unmodifiableMap(counts);
    }

    public String toJson() {
        String countsJson = counts().entrySet().stream()
                .map(entry -> JsonUtil.quote(entry.getKey().name()) + ":" + entry.getValue())
                .collect(Collectors.joining(",", "{", "}"));
        String entriesJson = entries.stream().map(ArchiveEntryInfo::toJson)
                .collect(Collectors.joining(",", "[", "]"));
        return "{" +
                "\"format\":" + JsonUtil.quote(format) + ',' +
                "\"fileCount\":" + fileCount() + ',' +
                "\"processableCount\":" + processableCount() + ',' +
                "\"excludedByDefaultCount\":" + excludedByDefaultCount() + ',' +
                "\"totalDeclaredSize\":" + totalDeclaredSize + ',' +
                "\"rejected\":" + rejected + ',' +
                "\"counts\":" + countsJson + ',' +
                "\"warnings\":" + JsonUtil.stringArray(warnings) + ',' +
                "\"includeUnprocessedConfirmation\":" + JsonUtil.quote(INCLUDE_UNPROCESSED_CONFIRMATION) + ',' +
                "\"entries\":" + entriesJson +
                "}";
    }

    public static final class Builder {
        private final String format;
        private final List<ArchiveEntryInfo> entries = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private long totalDeclaredSize;
        private boolean rejected;

        public Builder(String format) {
            this.format = format;
        }

        public void add(ArchiveEntryInfo entry) {
            entries.add(entry);
            if (!entry.directory() && entry.size() > 0) {
                totalDeclaredSize = Math.addExact(totalDeclaredSize, entry.size());
            }
        }

        public void warning(String warning) {
            if (!warnings.contains(warning)) {
                warnings.add(warning);
            }
        }

        public void reject(String warning) {
            rejected = true;
            warning(warning);
        }

        public ArchiveInspection build() {
            return new ArchiveInspection(format, entries, warnings, totalDeclaredSize, rejected);
        }
    }
}

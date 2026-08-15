/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.server;

import io.github.caipeijia833.docredaction.archive.ArchiveEntryAction;
import io.github.caipeijia833.docredaction.processor.ProcessReport;
import io.github.caipeijia833.docredaction.processor.WorkerTelemetry;
import io.github.caipeijia833.docredaction.util.JsonUtil;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

public final class JobRecord {
    private final String id;
    private final String projectId;
    private final String projectName;
    private final String originalName;
    private volatile String redactedName;
    private volatile List<String> selectedRuleCategories = List.of();
    private final Path inputPath;
    private final Instant createdAt;
    private volatile Instant updatedAt;
    private volatile JobStatus status;
    private volatile Path outputPath;
    private volatile Instant startedAt;
    private volatile Instant completedAt;
    private volatile String sha256;
    private volatile long size;
    private volatile String error;
    private volatile int unitsProcessed;
    private volatile String archiveFormat;
    private volatile boolean includeUnprocessed;
    private volatile Map<Integer, ArchiveEntryAction> archiveEntryActions = Map.of();
    private volatile String processingMode = "external_irreversible";
    private volatile Path vaultPath;
    private volatile int restoreCount;
    private volatile Instant lastRestoredAt;
    private volatile boolean reviewRequested;
    private volatile int retryCount;
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private volatile WorkerTelemetry workerTelemetry = WorkerTelemetry.unavailable(false, "not measured");

    public JobRecord(String id, String projectName, String originalName, Path inputPath,
            Instant createdAt, JobStatus status) {
        this(id, UUID.randomUUID().toString(), projectName, originalName, inputPath, createdAt, status);
    }

    public JobRecord(String id, String projectId, String projectName, String originalName, Path inputPath,
            Instant createdAt, JobStatus status) {
        this.id = id;
        this.projectId = projectId;
        this.projectName = projectName;
        this.originalName = originalName;
        this.inputPath = inputPath;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.status = status;
    }

    public synchronized void uploaded(String sha256, long size) {
        this.sha256 = sha256;
        this.size = size;
        this.status = JobStatus.UPLOADED;
        touch();
    }

    public synchronized void processing() {
        this.startedAt = Instant.now();
        this.status = JobStatus.PROCESSING;
        this.error = null;
        touch();
    }

    public synchronized void inspecting(String archiveFormat) {
        this.archiveFormat = archiveFormat;
        this.status = JobStatus.INSPECTING;
        this.error = null;
        touch();
    }

    public synchronized void awaitingConfirmation(String archiveFormat) {
        this.archiveFormat = archiveFormat;
        this.status = JobStatus.AWAITING_CONFIRMATION;
        this.error = null;
        touch();
    }

    public synchronized void analyzing() {
        this.status = JobStatus.ANALYZING;
        this.error = null;
        touch();
    }

    public synchronized void awaitingReview() {
        this.status = JobStatus.AWAITING_REVIEW;
        this.error = null;
        touch();
    }

    public synchronized void archiveConfirmed(boolean includeUnprocessed) {
        this.includeUnprocessed = includeUnprocessed;
        this.archiveEntryActions = Map.of();
        touch();
    }

    public synchronized void archiveConfirmed(Map<Integer, ArchiveEntryAction> actions) {
        this.archiveEntryActions = actions == null ? Map.of() : Map.copyOf(actions);
        this.includeUnprocessed = this.archiveEntryActions.containsValue(ArchiveEntryAction.KEEP_UNPROCESSED);
        touch();
    }

    public synchronized void securityMode(String processingMode, Path vaultPath) {
        this.processingMode = processingMode;
        this.vaultPath = vaultPath;
        touch();
    }

    public synchronized void reviewRequested(boolean reviewRequested) {
        this.reviewRequested = reviewRequested;
        touch();
    }

    public synchronized void filenamePolicy(String redactedName, List<String> selectedRuleCategories) {
        this.redactedName = redactedName;
        this.selectedRuleCategories = selectedRuleCategories == null
                ? List.of() : List.copyOf(selectedRuleCategories);
        touch();
    }

    public synchronized void restored() {
        restoreCount++;
        lastRestoredAt = Instant.now();
        touch();
    }

    public synchronized void completed(Path outputPath, ProcessReport report) {
        this.outputPath = outputPath;
        this.unitsProcessed = report.unitsProcessed();
        this.counts.clear();
        this.counts.putAll(report.counts());
        this.warnings.clear();
        this.warnings.addAll(report.warnings());
        this.workerTelemetry = report.workerTelemetry();
        this.completedAt = Instant.now();
        this.status = JobStatus.COMPLETED;
        touch();
    }

    public synchronized void failed(Throwable throwable) {
        this.error = safeError(throwable);
        this.completedAt = Instant.now();
        this.status = JobStatus.FAILED;
        touch();
    }

    public synchronized void interrupted(String reason) {
        this.error = reason;
        this.status = JobStatus.INTERRUPTED;
        this.completedAt = null;
        touch();
    }

    public synchronized void cancelled(String reason) {
        this.error = reason;
        this.completedAt = Instant.now();
        this.status = JobStatus.CANCELLED;
        touch();
    }

    public synchronized void retrying() {
        retryCount++;
        completedAt = null;
        error = null;
        status = JobStatus.UPLOADED;
        touch();
    }

    public String id() {
        return id;
    }

    public String projectId() {
        return projectId;
    }

    public String projectName() {
        return projectName;
    }

    public String originalName() {
        return originalName;
    }

    public String redactedName() {
        if (redactedName != null && !redactedName.isBlank()) {
            return redactedName;
        }
        return outputPath == null ? originalName : outputPath.getFileName().toString();
    }

    public List<String> selectedRuleCategories() {
        return selectedRuleCategories;
    }

    public Path inputPath() {
        return inputPath;
    }

    public JobStatus status() {
        return status;
    }

    public Path outputPath() {
        return outputPath;
    }

    public String archiveFormat() {
        return archiveFormat;
    }

    public boolean isArchive() {
        return archiveFormat != null && !archiveFormat.isBlank();
    }

    public Path vaultPath() {
        return vaultPath;
    }

    public boolean restorable() {
        return vaultPath != null && Files.isRegularFile(vaultPath);
    }

    public String processingMode() {
        return processingMode;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public boolean reviewRequested() {
        return reviewRequested;
    }

    public int retryCount() {
        return retryCount;
    }

    public int restoreCount() {
        return restoreCount;
    }

    public String sha256() {
        return sha256;
    }

    public long size() {
        return size;
    }

    public boolean includeUnprocessed() {
        return includeUnprocessed;
    }

    public Map<Integer, ArchiveEntryAction> archiveEntryActions() {
        return archiveEntryActions;
    }

    public synchronized Map<String, Integer> counts() {
        return Map.copyOf(counts);
    }

    public synchronized List<String> warnings() {
        return List.copyOf(warnings);
    }

    public synchronized int totalMatches() {
        long total = counts.values().stream().mapToLong(Integer::longValue).sum();
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    public WorkerTelemetry workerTelemetry() {
        return workerTelemetry;
    }

    public synchronized String toJson() {
        return "{" +
                "\"id\":" + JsonUtil.quote(id) + ',' +
                "\"projectId\":" + JsonUtil.quote(projectId) + ',' +
                "\"projectName\":" + JsonUtil.quote(projectName) + ',' +
                "\"originalName\":" + JsonUtil.quote(originalName) + ',' +
                "\"redactedName\":" + JsonUtil.quote(redactedName()) + ',' +
                "\"selectedRuleCategories\":" + JsonUtil.stringArray(selectedRuleCategories) + ',' +
                "\"status\":" + JsonUtil.quote(status.name()) + ',' +
                "\"createdAt\":" + JsonUtil.quote(createdAt.toString()) + ',' +
                "\"updatedAt\":" + JsonUtil.quote(updatedAt.toString()) + ',' +
                "\"startedAt\":" + JsonUtil.quote(startedAt == null ? null : startedAt.toString()) + ',' +
                "\"completedAt\":" + JsonUtil.quote(completedAt == null ? null : completedAt.toString()) + ',' +
                "\"sha256\":" + JsonUtil.quote(sha256) + ',' +
                "\"size\":" + size + ',' +
                "\"unitsProcessed\":" + unitsProcessed + ',' +
                "\"totalMatches\":" + totalMatches() + ',' +
                "\"counts\":" + JsonUtil.stringMap(counts) + ',' +
                "\"warnings\":" + JsonUtil.stringArray(warnings) + ',' +
                "\"workerTelemetry\":" + workerTelemetry.toJson() + ',' +
                "\"error\":" + JsonUtil.quote(error) + ',' +
                "\"archiveFormat\":" + JsonUtil.quote(archiveFormat) + ',' +
                "\"confirmationRequired\":" + (status == JobStatus.AWAITING_CONFIRMATION) + ',' +
                "\"reviewRequired\":" + (status == JobStatus.AWAITING_REVIEW) + ',' +
                "\"includeUnprocessed\":" + includeUnprocessed + ',' +
                "\"archiveEntryDecisionCount\":" + archiveEntryActions.size() + ',' +
                "\"processingMode\":" + JsonUtil.quote(processingMode) + ',' +
                "\"restorable\":" + restorable() + ',' +
                "\"restoreCount\":" + restoreCount + ',' +
                "\"lastRestoredAt\":" + JsonUtil.quote(lastRestoredAt == null ? null : lastRestoredAt.toString()) + ',' +
                "\"retryCount\":" + retryCount + ',' +
                "\"cancelAvailable\":" + (status == JobStatus.UPLOADED || status == JobStatus.INSPECTING
                        || status == JobStatus.AWAITING_CONFIRMATION || status == JobStatus.ANALYZING
                        || status == JobStatus.AWAITING_REVIEW || status == JobStatus.PROCESSING) + ',' +
                "\"retryAvailable\":" + (status == JobStatus.INTERRUPTED) + ',' +
                "\"deleteAvailable\":" + (status != JobStatus.UPLOADED && status != JobStatus.INSPECTING
                        && status != JobStatus.ANALYZING && status != JobStatus.PROCESSING) + ',' +
                "\"downloadAvailable\":" + (status == JobStatus.COMPLETED && outputPath != null) +
                "}";
    }

    public synchronized Properties toProperties() {
        Properties properties = new Properties();
        properties.setProperty("id", id);
        properties.setProperty("projectId", projectId);
        properties.setProperty("projectName", projectName);
        properties.setProperty("originalName", originalName);
        put(properties, "redactedName", redactedName);
        properties.setProperty("selectedRuleCategories", String.join(",", selectedRuleCategories));
        properties.setProperty("inputPath", inputPath.toString());
        properties.setProperty("createdAt", createdAt.toString());
        properties.setProperty("updatedAt", updatedAt.toString());
        properties.setProperty("status", status.name());
        put(properties, "outputPath", outputPath == null ? null : outputPath.toString());
        put(properties, "startedAt", startedAt == null ? null : startedAt.toString());
        put(properties, "completedAt", completedAt == null ? null : completedAt.toString());
        put(properties, "sha256", sha256);
        properties.setProperty("size", Long.toString(size));
        properties.setProperty("unitsProcessed", Integer.toString(unitsProcessed));
        put(properties, "error", error);
        put(properties, "archiveFormat", archiveFormat);
        properties.setProperty("includeUnprocessed", Boolean.toString(includeUnprocessed));
        archiveEntryActions.forEach((index, action) ->
                properties.setProperty("archiveAction." + index, action.name()));
        properties.setProperty("processingMode", processingMode);
        put(properties, "vaultPath", vaultPath == null ? null : vaultPath.toString());
        properties.setProperty("restoreCount", Integer.toString(restoreCount));
        put(properties, "lastRestoredAt", lastRestoredAt == null ? null : lastRestoredAt.toString());
        properties.setProperty("reviewRequested", Boolean.toString(reviewRequested));
        properties.setProperty("retryCount", Integer.toString(retryCount));
        properties.setProperty("worker.windowsJobObjectApplied",
                Boolean.toString(workerTelemetry.windowsJobObjectApplied()));
        properties.setProperty("worker.isolationRequired",
                Boolean.toString(workerTelemetry.isolationRequired()));
        properties.setProperty("worker.committedMemoryLimitBytes",
                Long.toString(workerTelemetry.committedMemoryLimitBytes()));
        properties.setProperty("worker.peakJobCommittedBytes",
                Long.toString(workerTelemetry.peakJobCommittedBytes()));
        properties.setProperty("worker.peakProcessCommittedBytes",
                Long.toString(workerTelemetry.peakProcessCommittedBytes()));
        properties.setProperty("worker.baselineJobDirectoryBytes",
                Long.toString(workerTelemetry.baselineJobDirectoryBytes()));
        properties.setProperty("worker.peakJobDirectoryBytes",
                Long.toString(workerTelemetry.peakJobDirectoryBytes()));
        properties.setProperty("worker.peakAdditionalDiskBytes",
                Long.toString(workerTelemetry.peakAdditionalDiskBytes()));
        properties.setProperty("worker.status", workerTelemetry.status());
        counts.forEach((key, value) -> properties.setProperty("count." + key, Integer.toString(value)));
        for (int i = 0; i < warnings.size(); i++) {
            properties.setProperty("warning." + i, warnings.get(i));
        }
        return properties;
    }

    public static JobRecord fromProperties(Properties properties) {
        String id = properties.getProperty("id");
        String projectId = properties.getProperty("projectId");
        if (projectId == null || projectId.isBlank()) {
            projectId = UUID.nameUUIDFromBytes(("legacy:" + id).getBytes(StandardCharsets.UTF_8)).toString();
        }
        JobRecord record = new JobRecord(
                id,
                projectId,
                properties.getProperty("projectName", "未命名项目"),
                properties.getProperty("originalName", "unknown"),
                Path.of(properties.getProperty("inputPath")),
                Instant.parse(properties.getProperty("createdAt")),
                JobStatus.valueOf(properties.getProperty("status", "FAILED")));
        record.outputPath = pathOrNull(properties.getProperty("outputPath"));
        record.redactedName = properties.getProperty("redactedName");
        String selectedCategories = properties.getProperty("selectedRuleCategories", "");
        record.selectedRuleCategories = selectedCategories.isBlank() ? List.of()
                : List.of(selectedCategories.split(",")).stream()
                        .map(String::trim).filter(value -> !value.isBlank()).toList();
        record.startedAt = instantOrNull(properties.getProperty("startedAt"));
        record.completedAt = instantOrNull(properties.getProperty("completedAt"));
        record.sha256 = properties.getProperty("sha256");
        record.size = Long.parseLong(properties.getProperty("size", "0"));
        record.unitsProcessed = Integer.parseInt(properties.getProperty("unitsProcessed", "0"));
        record.error = properties.getProperty("error");
        record.archiveFormat = properties.getProperty("archiveFormat");
        record.includeUnprocessed = Boolean.parseBoolean(properties.getProperty("includeUnprocessed", "false"));
        Map<Integer, ArchiveEntryAction> archiveActions = new LinkedHashMap<>();
        properties.stringPropertyNames().stream()
                .filter(key -> key.startsWith("archiveAction."))
                .sorted()
                .forEach(key -> {
                    try {
                        int index = Integer.parseInt(key.substring("archiveAction.".length()));
                        ArchiveEntryAction action = ArchiveEntryAction.valueOf(properties.getProperty(key));
                        if (index > 0) {
                            archiveActions.put(index, action);
                        }
                    } catch (IllegalArgumentException ignored) {
                        // Ignore a corrupt optional decision while preserving the rest of the job record.
                    }
                });
        record.archiveEntryActions = Map.copyOf(archiveActions);
        if (!record.archiveEntryActions.isEmpty()) {
            record.includeUnprocessed = record.archiveEntryActions.containsValue(ArchiveEntryAction.KEEP_UNPROCESSED);
        }
        record.processingMode = properties.getProperty("processingMode", "external_irreversible");
        record.vaultPath = pathOrNull(properties.getProperty("vaultPath"));
        record.restoreCount = Integer.parseInt(properties.getProperty("restoreCount", "0"));
        record.lastRestoredAt = instantOrNull(properties.getProperty("lastRestoredAt"));
        record.updatedAt = instantOrNull(properties.getProperty("updatedAt"));
        if (record.updatedAt == null) {
            record.updatedAt = record.createdAt;
        }
        record.reviewRequested = Boolean.parseBoolean(properties.getProperty("reviewRequested", "false"));
        record.retryCount = Integer.parseInt(properties.getProperty("retryCount", "0"));
        record.workerTelemetry = new WorkerTelemetry(
                Boolean.parseBoolean(properties.getProperty("worker.windowsJobObjectApplied", "false")),
                Boolean.parseBoolean(properties.getProperty("worker.isolationRequired", "false")),
                parseLong(properties, "worker.committedMemoryLimitBytes"),
                parseLong(properties, "worker.peakJobCommittedBytes"),
                parseLong(properties, "worker.peakProcessCommittedBytes"),
                parseLong(properties, "worker.baselineJobDirectoryBytes"),
                parseLong(properties, "worker.peakJobDirectoryBytes"),
                parseLong(properties, "worker.peakAdditionalDiskBytes"),
                properties.getProperty("worker.status", "not measured"));
        properties.stringPropertyNames().stream()
                .filter(key -> key.startsWith("count."))
                .sorted()
                .forEach(key -> record.counts.put(key.substring(6), Integer.parseInt(properties.getProperty(key))));
        properties.stringPropertyNames().stream()
                .filter(key -> key.startsWith("warning."))
                .sorted()
                .forEach(key -> record.warnings.add(properties.getProperty(key)));
        return record;
    }

    private void touch() {
        updatedAt = Instant.now();
    }

    private static void put(Properties properties, String key, String value) {
        if (value != null) {
            properties.setProperty(key, value);
        }
    }

    private static Path pathOrNull(String value) {
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private static Instant instantOrNull(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private static long parseLong(Properties properties, String key) {
        try {
            return Long.parseLong(properties.getProperty(key, "0"));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static String safeError(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getClass().getSimpleName();
        }
        message = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}

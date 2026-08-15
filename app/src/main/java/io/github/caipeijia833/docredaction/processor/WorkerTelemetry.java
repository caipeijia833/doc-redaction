/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import io.github.caipeijia833.docredaction.util.JsonUtil;

/**
 * Parent-side resource observations for one disposable document worker.
 *
 * <p>The values are operational evidence, not a security-boundary claim. On
 * Windows, {@code windowsJobObjectApplied} means the process tree was assigned
 * to a Job Object with kill-on-close, active-process and committed-memory
 * limits. File and network isolation require a separate OS sandbox.</p>
 */
public record WorkerTelemetry(
        boolean windowsJobObjectApplied,
        boolean isolationRequired,
        long committedMemoryLimitBytes,
        long peakJobCommittedBytes,
        long peakProcessCommittedBytes,
        long baselineJobDirectoryBytes,
        long peakJobDirectoryBytes,
        long peakAdditionalDiskBytes,
        String status) {

    public WorkerTelemetry {
        committedMemoryLimitBytes = nonNegative(committedMemoryLimitBytes);
        peakJobCommittedBytes = nonNegative(peakJobCommittedBytes);
        peakProcessCommittedBytes = nonNegative(peakProcessCommittedBytes);
        baselineJobDirectoryBytes = nonNegative(baselineJobDirectoryBytes);
        peakJobDirectoryBytes = nonNegative(peakJobDirectoryBytes);
        peakAdditionalDiskBytes = nonNegative(peakAdditionalDiskBytes);
        status = status == null || status.isBlank() ? "unavailable" : status.trim();
    }

    public static WorkerTelemetry unavailable(boolean required, String status) {
        return new WorkerTelemetry(false, required, 0L, 0L, 0L, 0L, 0L, 0L, status);
    }

    public String toJson() {
        return "{" +
                "\"windowsJobObjectApplied\":" + windowsJobObjectApplied + ',' +
                "\"isolationRequired\":" + isolationRequired + ',' +
                "\"committedMemoryLimitBytes\":" + committedMemoryLimitBytes + ',' +
                "\"peakJobCommittedBytes\":" + peakJobCommittedBytes + ',' +
                "\"peakProcessCommittedBytes\":" + peakProcessCommittedBytes + ',' +
                "\"baselineJobDirectoryBytes\":" + baselineJobDirectoryBytes + ',' +
                "\"peakJobDirectoryBytes\":" + peakJobDirectoryBytes + ',' +
                "\"peakAdditionalDiskBytes\":" + peakAdditionalDiskBytes + ',' +
                "\"status\":" + JsonUtil.quote(status) +
                "}";
    }

    public WorkerTelemetry merge(WorkerTelemetry other) {
        if (other == null) {
            return this;
        }
        String mergedStatus = status.equals(other.status) ? status : status + "; " + other.status;
        return new WorkerTelemetry(
                windowsJobObjectApplied || other.windowsJobObjectApplied,
                isolationRequired || other.isolationRequired,
                Math.max(committedMemoryLimitBytes, other.committedMemoryLimitBytes),
                Math.max(peakJobCommittedBytes, other.peakJobCommittedBytes),
                Math.max(peakProcessCommittedBytes, other.peakProcessCommittedBytes),
                Math.max(baselineJobDirectoryBytes, other.baselineJobDirectoryBytes),
                Math.max(peakJobDirectoryBytes, other.peakJobDirectoryBytes),
                Math.max(peakAdditionalDiskBytes, other.peakAdditionalDiskBytes),
                mergedStatus);
    }

    private static long nonNegative(long value) {
        return Math.max(0L, value);
    }
}

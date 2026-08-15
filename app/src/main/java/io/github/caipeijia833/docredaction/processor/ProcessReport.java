/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import io.github.caipeijia833.docredaction.rules.RedactionResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ProcessReport {
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private int unitsProcessed;
    private WorkerTelemetry workerTelemetry = WorkerTelemetry.unavailable(false, "not measured");

    public void add(RedactionResult result) {
        result.counts().forEach((key, value) -> counts.merge(key, value, Integer::sum));
    }

    public void addCount(String ruleId, int count) {
        if (count > 0) {
            counts.merge(ruleId, count, Integer::sum);
        }
    }

    public void warning(String warning) {
        if (warning != null && !warning.isBlank() && !warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    public void unitProcessed() {
        unitsProcessed++;
    }

    public void addUnits(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("处理单元数不能为负数");
        }
        unitsProcessed = Math.addExact(unitsProcessed, count);
    }

    public void merge(ProcessReport other) {
        if (other == null) {
            return;
        }
        other.counts.forEach((key, value) -> counts.merge(key, value, Integer::sum));
        for (String warning : other.warnings) {
            warning(warning);
        }
        unitsProcessed += other.unitsProcessed;
        workerTelemetry = workerTelemetry.merge(other.workerTelemetry);
    }

    public int unitsProcessed() {
        return unitsProcessed;
    }

    public int totalMatches() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public Map<String, Integer> counts() {
        return Collections.unmodifiableMap(counts);
    }

    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    public WorkerTelemetry workerTelemetry() {
        return workerTelemetry;
    }

    public void workerTelemetry(WorkerTelemetry telemetry) {
        if (telemetry != null) {
            workerTelemetry = telemetry;
        }
    }
}

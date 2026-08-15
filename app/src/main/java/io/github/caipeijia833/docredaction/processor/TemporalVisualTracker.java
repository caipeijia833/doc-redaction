/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Adds conservative mask bridges between compatible detections in adjacent sampled frames. */
final class TemporalVisualTracker {
    private static final int MAX_ACTIVE_TRACKS_PER_KEY = 128;

    private TemporalVisualTracker() {
    }

    static Result bridge(List<MediaFinding> source, double sampleFps) {
        long intervalMillis = Math.max(100L, Math.round(1_000.0d / Math.max(0.1d, sampleFps)));
        long maximumCenterGap = Math.multiplyExact(intervalMillis, 2L);
        List<MediaFinding> ordered = source.stream()
                .filter(MediaFinding::visual)
                .sorted(Comparator.comparingLong(TemporalVisualTracker::centerMillis)
                        .thenComparingInt(MediaFinding::streamIndex)
                        .thenComparing(MediaFinding::ruleId))
                .toList();
        Map<Key, List<MediaFinding>> active = new HashMap<>();
        List<MediaFinding> bridges = new ArrayList<>();
        for (MediaFinding current : ordered) {
            Key key = Key.from(current);
            List<MediaFinding> candidates = active.computeIfAbsent(key, ignored -> new ArrayList<>());
            long center = centerMillis(current);
            candidates.removeIf(previous -> center - centerMillis(previous) > maximumCenterGap);
            MediaFinding best = null;
            double bestScore = Double.POSITIVE_INFINITY;
            for (MediaFinding previous : candidates) {
                long delta = center - centerMillis(previous);
                if (delta <= 0L || delta > maximumCenterGap || !spatiallyCompatible(previous, current)) {
                    continue;
                }
                double score = normalizedCenterDistance(previous, current) - previous.intersectionOverUnion(current);
                if (score < bestScore) {
                    best = previous;
                    bestScore = score;
                }
            }
            if (best != null) {
                MediaFinding bridge = bridge(best, current);
                if (bridge != null) {
                    bridges.add(bridge);
                }
                candidates.remove(best);
            }
            candidates.add(current);
            if (candidates.size() > MAX_ACTIVE_TRACKS_PER_KEY) {
                candidates.removeFirst();
            }
        }
        List<MediaFinding> augmented = new ArrayList<>(source.size() + bridges.size());
        augmented.addAll(source);
        augmented.addAll(bridges);
        augmented.sort(Comparator.comparingInt(MediaFinding::streamIndex)
                .thenComparingLong(MediaFinding::startMillis)
                .thenComparing(MediaFinding::ruleId));
        return new Result(List.copyOf(augmented), bridges.size());
    }

    private static boolean spatiallyCompatible(MediaFinding first, MediaFinding second) {
        if (first.intersectionOverUnion(second) >= 0.02d) {
            return true;
        }
        double maximumMotion = Math.max(32.0d, 4.0d * Math.max(
                Math.max(first.width(), first.height()), Math.max(second.width(), second.height())));
        return centerDistance(first, second) <= maximumMotion;
    }

    private static double normalizedCenterDistance(MediaFinding first, MediaFinding second) {
        double scale = Math.max(1.0d, Math.max(
                Math.max(first.width(), first.height()), Math.max(second.width(), second.height())));
        return centerDistance(first, second) / scale;
    }

    private static double centerDistance(MediaFinding first, MediaFinding second) {
        double firstX = first.x() + first.width() / 2.0d;
        double firstY = first.y() + first.height() / 2.0d;
        double secondX = second.x() + second.width() / 2.0d;
        double secondY = second.y() + second.height() / 2.0d;
        return Math.hypot(firstX - secondX, firstY - secondY);
    }

    private static MediaFinding bridge(MediaFinding first, MediaFinding second) {
        long start = centerMillis(first);
        long end = centerMillis(second);
        if (end <= start) {
            return null;
        }
        int padding = Math.max(3, (int) Math.ceil(centerDistance(first, second) * 0.08d));
        int left = Math.max(0, Math.min(first.x(), second.x()) - padding);
        int top = Math.max(0, Math.min(first.y(), second.y()) - padding);
        int right = Math.max(first.x() + first.width(), second.x() + second.width()) + padding;
        int bottom = Math.max(first.y() + first.height(), second.y() + second.height()) + padding;
        return new MediaFinding(first.kind(), first.streamIndex(), first.ruleId(), first.label(),
                first.value().isBlank() ? second.value() : first.value(),
                first.context().isBlank() ? second.context() : first.context(),
                start, end, left, top, right - left, bottom - top,
                Math.min(first.confidence(), second.confidence()));
    }

    private static long centerMillis(MediaFinding finding) {
        return finding.startMillis() + (finding.endMillis() - finding.startMillis()) / 2L;
    }

    record Result(List<MediaFinding> findings, int bridgeCount) {
    }

    private record Key(int streamIndex, MediaFinding.Kind kind, String ruleId, String value) {
        static Key from(MediaFinding finding) {
            String stableValue = finding.value().isBlank()
                    ? "" : finding.value().trim().toLowerCase(Locale.ROOT);
            return new Key(finding.streamIndex(), finding.kind(), finding.ruleId(), stableValue);
        }
    }
}

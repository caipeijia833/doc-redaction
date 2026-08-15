/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import io.github.caipeijia833.docredaction.review.ReviewInspection;
import io.github.caipeijia833.docredaction.review.ReviewInspector;
import io.github.caipeijia833.docredaction.review.ReviewItem;
import io.github.caipeijia833.docredaction.rules.RuleEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Public bridge that exposes media findings without leaking internal native models. */
public final class MediaReviewInspector {
    public ReviewInspection inspect(Path file, RuleEngine rules) throws Exception {
        Path work = Files.createTempDirectory("doc-redaction-media-review-");
        try {
            MediaAnalysis analysis = new MediaAnalyzer().analyze(file, rules, work);
            List<ReviewItem> items = new ArrayList<>();
            boolean truncated = false;
            for (MediaFinding finding : analysis.findings()) {
                if (items.size() >= ReviewInspector.MAX_ITEMS) {
                    truncated = true;
                    break;
                }
                String displayValue = finding.value().isBlank()
                        ? finding.label() : finding.value();
                String location = "轨道" + finding.streamIndex() + " "
                        + formatTime(finding.startMillis()) + "–" + formatTime(finding.endMillis());
                items.add(new ReviewItem(items.size() + 1, finding.ruleId(), finding.label(),
                        location, displayValue, finding.context(), finding.kind().name(),
                        finding.streamIndex(), finding.startMillis(), finding.endMillis(),
                        finding.x(), finding.y(), finding.width(), finding.height(),
                        finding.confidence(), finding.ignoreKey()));
            }
            List<String> warnings = new ArrayList<>(analysis.warnings());
            if (truncated) {
                warnings.add("音视频命中超过" + ReviewInspector.MAX_ITEMS
                        + "项，复核列表已截断；处理仍会覆盖全部已检测命中。建议拆分文件人工复核。");
            }
            return new ReviewInspection(items, warnings, truncated);
        } finally {
            deleteTree(work);
        }
    }

    private static String formatTime(long millis) {
        long totalSeconds = millis / 1_000L;
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d.%03d",
                totalSeconds / 3_600L, totalSeconds % 3_600L / 60L,
                totalSeconds % 60L, millis % 1_000L);
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}

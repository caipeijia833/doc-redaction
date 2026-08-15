/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import io.github.caipeijia833.docredaction.review.ReviewInspection;
import io.github.caipeijia833.docredaction.review.ReviewInspector;
import io.github.caipeijia833.docredaction.rules.RuleEngine;

import java.io.IOException;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class ResidualScanner {
    private ResidualScanner() {
    }

    public static void verify(Path original, Path output, RuleEngine rules, ProcessReport report) throws Exception {
        if (MediaProbe.isSupportedFileName(original.getFileName().toString())) {
            verifyMediaOutput(original, output, rules, report);
            return;
        }
        if (isImage(original)) {
            verifyImageOutput(output, rules, report);
            return;
        }
        OoxmlPackageProcessor.Kind officeKind = officeKind(original);
        if (officeKind != null) {
            verifyCompleteOfficeOutput(original, output, officeKind, rules, report);
            return;
        }
        ReviewInspection before = new ReviewInspector(rules).inspect(original);
        ReviewInspection after = new ReviewInspector(rules)
                .inspect(output, original.getFileName().toString());
        Set<String> expectedRemoved = new LinkedHashSet<>();
        before.items().forEach(item -> expectedRemoved.add(item.value()));
        Set<String> outputValues = new LinkedHashSet<>();
        after.items().forEach(item -> outputValues.add(item.value()));
        expectedRemoved.retainAll(outputValues);
        if (!expectedRemoved.isEmpty()) {
            throw new IOException("残留扫描失败：输出中仍存在" + expectedRemoved.size() + "个应脱敏原值");
        }
        if (before.truncated() || after.truncated()) {
            report.warning("残留扫描命中项超过5000条，自动验证范围被截断；必须拆分文件并人工复核。");
        } else {
            report.warning("已执行格式文本残留复扫，未发现本次规则应移除的原值。");
        }
    }

    private static void verifyImageOutput(Path output, RuleEngine rules, ProcessReport report) throws Exception {
        ImageResourceGuard.inspect(output);
        BufferedImage image = ImageIO.read(output.toFile());
        if (image == null) throw new IOException("无法解码脱敏图像进行残留复扫");
        Path work = output.getParent().resolve("residual-image-ocr-" + UUID.randomUUID()).normalize();
        try {
            LocalOcrEngine ocr = new LocalOcrEngine();
            if (!ocr.capability().available()) throw new IOException(ocr.capability().message());
            var detections = ocr.detect(image, rules, work, "residual-image");
            if (!detections.isEmpty()) {
                var first = detections.getFirst();
                throw new IOException("图像残留扫描失败：输出中仍存在" + detections.size()
                        + "个规则命中，首项规则=" + first.ruleId());
            }
            report.warning("已对脱敏图像重新执行本地OCR残留复扫，未发现规则命中。");
        } finally {
            deleteTree(work);
        }
    }

    private static boolean isImage(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".bmp");
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Job-level cleanup retries locked temporary files.
        }
    }

    private static void verifyMediaOutput(Path original, Path output, RuleEngine rules,
            ProcessReport report) throws Exception {
        ReviewInspection after = new ReviewInspector(rules)
                .inspect(output, original.getFileName().toString());
        if (!after.items().isEmpty()) {
            var first = after.items().getFirst();
            throw new IOException("音视频残留扫描失败：输出中仍存在" + after.items().size()
                    + "个敏感命中，首项规则=" + first.ruleId() + "，位置=" + first.location());
        }
        if (after.truncated()) {
            throw new IOException("音视频残留扫描结果被截断，无法证明输出安全");
        }
        report.warning("已对音视频输出重新执行本地ASR、抽帧OCR和视觉残留复扫，未发现规则命中。");
    }

    private static void verifyCompleteOfficeOutput(Path original, Path output, OoxmlPackageProcessor.Kind kind,
            RuleEngine rules, ProcessReport report) throws Exception {
        Set<String> originalValues = rules.usesStablePseudonyms()
                ? collectOriginalValues(original, kind, rules) : Set.of();
        String[] residual = new String[2];
        OoxmlPackageProcessor.scanText(output, kind, (location, text) -> {
            var matches = rules.detect(text);
            for (var match : matches) {
                String value = text.substring(match.start(), match.end());
                boolean remains = rules.usesStablePseudonyms()
                        ? originalValues.contains(value) : containsUnmaskedData(value);
                if (remains) {
                    residual[0] = location;
                    residual[1] = match.ruleId();
                    return false;
                }
            }
            return true;
        });
        if (residual[0] != null) {
            throw new IOException("残留扫描失败：Office输出中仍存在规则命中，位置="
                    + residual[0] + "，规则=" + residual[1]);
        }
        report.warning("已对Office输出执行完整的包级事件流残留复扫，未因命中数量截断。");
    }

    private static Set<String> collectOriginalValues(Path original, OoxmlPackageProcessor.Kind kind,
            RuleEngine rules) throws Exception {
        Set<String> values = new LinkedHashSet<>();
        OoxmlPackageProcessor.scanText(original, kind, (location, text) -> {
            for (var match : rules.detect(text)) {
                values.add(text.substring(match.start(), match.end()));
                if (values.size() > 100_000) {
                    throw new IllegalStateException("一致替换模式的唯一敏感原值超过100000项安全上限");
                }
            }
            return true;
        });
        return values;
    }

    private static boolean containsUnmaskedData(String value) {
        if (value.equals("已脱敏") || value.equalsIgnoreCase("redacted")) return false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current != '＊' && (Character.isLetterOrDigit(current)
                    || Character.UnicodeScript.of(current) == Character.UnicodeScript.HAN)) {
                return true;
            }
        }
        return false;
    }

    private static OoxmlPackageProcessor.Kind officeKind(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".docx")) return OoxmlPackageProcessor.Kind.DOCX;
        if (name.endsWith(".xlsx")) return OoxmlPackageProcessor.Kind.XLSX;
        if (name.endsWith(".pptx")) return OoxmlPackageProcessor.Kind.PPTX;
        return null;
    }
}

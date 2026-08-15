/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.review;

import io.github.caipeijia833.docredaction.rules.RuleEngine;
import io.github.caipeijia833.docredaction.rules.SensitiveMatch;
import io.github.caipeijia833.docredaction.processor.OoxmlPackageProcessor;
import io.github.caipeijia833.docredaction.processor.MediaReviewInspector;
import io.github.caipeijia833.docredaction.processor.MediaProbe;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ReviewInspector {
    public static final int MAX_ITEMS = 5_000;
    private final RuleEngine ruleEngine;

    public ReviewInspector(RuleEngine ruleEngine) {
        this.ruleEngine = ruleEngine;
    }

    public ReviewInspection inspect(Path file) throws Exception {
        return inspect(file, file.getFileName().toString());
    }

    public ReviewInspection inspect(Path file, String logicalFileName) throws Exception {
        Collector collector = new Collector(ruleEngine);
        String name = logicalFileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".docx")) {
            inspectDocx(file, collector);
        } else if (name.endsWith(".xlsx")) {
            inspectXlsx(file, collector);
        } else if (name.endsWith(".pptx")) {
            inspectPptx(file, collector);
        } else if (name.endsWith(".pdf")) {
            inspectPdf(file, collector);
        } else if (MediaProbe.isSupportedFileName(name)) {
            return new MediaReviewInspector().inspect(file, ruleEngine);
        } else {
            throw new IOException("当前格式不支持处理前复核");
        }
        return collector.result();
    }

    private static void inspectDocx(Path file, Collector collector) throws IOException {
        scanOoxml(file, OoxmlPackageProcessor.Kind.DOCX, collector);
    }

    private static void inspectXlsx(Path file, Collector collector) throws Exception {
        scanOoxml(file, OoxmlPackageProcessor.Kind.XLSX, collector);
    }

    private static void inspectPptx(Path file, Collector collector) throws IOException {
        scanOoxml(file, OoxmlPackageProcessor.Kind.PPTX, collector);
    }

    private static void scanOoxml(Path file, OoxmlPackageProcessor.Kind kind, Collector collector) throws IOException {
        try {
            OoxmlPackageProcessor.scanText(file, kind, (location, text) -> {
                collector.add(location, text);
                return !collector.full();
            });
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("无法流式检查Office文档", ex);
        }
    }

    private static void inspectPdf(Path file, Collector collector) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                collector.add("PDF第" + page + "页", stripper.getText(document));
                if (collector.full()) {
                    return;
                }
            }
        }
    }

    private static final class Collector {
        private final RuleEngine engine;
        private final List<ReviewItem> items = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private boolean truncated;

        private Collector(RuleEngine engine) {
            this.engine = engine;
        }

        void add(String location, String text) {
            if (text == null || text.isBlank() || full()) {
                return;
            }
            for (SensitiveMatch match : engine.detect(text)) {
                if (full()) {
                    truncated = true;
                    break;
                }
                int contextStart = Math.max(0, match.start() - 24);
                int contextEnd = Math.min(text.length(), match.end() + 24);
                String context = text.substring(contextStart, contextEnd).replaceAll("[\\r\\n\\t]+", " ");
                items.add(new ReviewItem(items.size() + 1, match.ruleId(), match.label(), location,
                        text.substring(match.start(), match.end()), context));
            }
        }

        boolean full() {
            return items.size() >= MAX_ITEMS;
        }

        ReviewInspection result() {
            if (truncated || full()) {
                warnings.add("命中项超过" + MAX_ITEMS + "条，复核列表已截断；建议拆分文档或卷宗后处理。");
            }
            return new ReviewInspection(items, warnings, truncated || full());
        }
    }
}

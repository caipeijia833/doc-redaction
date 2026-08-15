/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import io.github.caipeijia833.docredaction.rules.SensitiveMatch;
import io.github.caipeijia833.docredaction.rules.RuleEngine;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class PdfProcessor implements DocumentProcessor {
    private static final float DPI = 144f;
    private static final float SCALE = DPI / 72f;

    @Override
    public Set<String> extensions() {
        return Set.of("pdf");
    }

    @Override
    public ProcessReport process(Path input, Path output, RuleEngine ruleEngine) throws Exception {
        ProcessReport report = new ProcessReport();
        LocalOcrEngine ocr = new LocalOcrEngine();
        Path ocrWork = output.getParent().resolve("pdf-ocr-" + UUID.randomUUID()).normalize();
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        try {
            try (PDDocument source = Loader.loadPDF(input.toFile(), IOUtils.createTempFileOnlyStreamCache());
                 PDDocument target = new PDDocument(IOUtils.createTempFileOnlyStreamCache())) {
                PDFRenderer renderer = new PDFRenderer(source);
                for (int pageIndex = 0; pageIndex < source.getNumberOfPages(); pageIndex++) {
                    ProcessReport textReport = new ProcessReport();
                    PageCollector collector = new PageCollector(ruleEngine, textReport);
                    collector.setSortByPosition(true);
                    collector.setStartPage(pageIndex + 1);
                    collector.setEndPage(pageIndex + 1);
                    collector.getText(source);

                    BufferedImage image = renderer.renderImageWithDPI(pageIndex, DPI, ImageType.RGB);
                    report.merge(textReport);
                    Graphics2D graphics = image.createGraphics();
                    try {
                        graphics.setColor(Color.BLACK);
                        for (Box box : collector.boxes()) {
                            int x = Math.max(0, Math.round(box.x() * SCALE) - 3);
                            int y = Math.max(0, Math.round(box.y() * SCALE) - 3);
                            int width = Math.min(image.getWidth() - x,
                                    Math.max(2, Math.round(box.width() * SCALE) + 6));
                            int height = Math.min(image.getHeight() - y,
                                    Math.max(2, Math.round(box.height() * SCALE) + 6));
                            graphics.fillRect(x, y, width, height);
                        }
                    } finally {
                        graphics.dispose();
                    }
                    // OCR every already-masked page. Images can be nested in Form
                    // XObjects and other render paths that direct resource scans miss.
                    if (!ocr.capability().available()) {
                        throw new IOException("PDF脱敏要求本地OCR逐页复核；" + ocr.capability().message());
                    }
                    ocr.redact(image, ruleEngine, report, ocrWork, "page-" + (pageIndex + 1));

                    float pageWidth = image.getWidth() / SCALE;
                    float pageHeight = image.getHeight() / SCALE;
                    PDPage outputPage = new PDPage(new PDRectangle(pageWidth, pageHeight));
                    target.addPage(outputPage);
                    PDImageXObject pageImage = JPEGFactory.createFromImage(target, image, 0.92f, Math.round(DPI));
                    try (PDPageContentStream content = new PDPageContentStream(target, outputPage)) {
                        content.drawImage(pageImage, 0, 0, pageWidth, pageHeight);
                    }
                    report.unitProcessed();
                }
                target.getDocumentInformation().setAuthor("");
                target.getDocumentInformation().setCreator("智能文档脱敏系统");
                target.getDocumentInformation().setProducer("智能文档脱敏系统");
                target.getDocumentInformation().setSubject("");
                target.getDocumentInformation().setKeywords("");
                target.save(output.toFile());
            }
        } finally {
            deleteTree(ocrWork);
        }
        report.warning("PDF采用高安全栅格化输出：原文本层、批注、表单、附件和脚本不进入结果文件，但搜索和编辑能力会降低。");
        report.warning("PDF每一页均已使用本地Tesseract OCR复核视觉内容；OCR结果未写入文本层。");
        return report;
    }

    private static void deleteTree(Path target) {
        if (!Files.exists(target)) {
            return;
        }
        try (var paths = Files.walk(target)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Job-level cleanup retries locked temporary files.
        }
    }

    private static final class PageCollector extends PDFTextStripper {
        private final RuleEngine ruleEngine;
        private final ProcessReport report;
        private final List<Box> boxes = new ArrayList<>();

        private PageCollector(RuleEngine ruleEngine, ProcessReport report) throws IOException {
            this.ruleEngine = ruleEngine;
            this.report = report;
        }

        List<Box> boxes() {
            return boxes;
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            List<SensitiveMatch> matches = ruleEngine.detect(text);
            for (SensitiveMatch match : matches) {
                Box box = toBox(text, positions, match.start(), match.end());
                if (box != null) {
                    boxes.add(box);
                    report.addCount(match.ruleId(), 1);
                }
            }
        }

        private static Box toBox(String text, List<TextPosition> positions, int start, int end) {
            if (positions.isEmpty()) {
                return null;
            }
            List<TextPosition> characterMap = new ArrayList<>();
            for (TextPosition position : positions) {
                String unicode = position.getUnicode();
                int length = unicode == null || unicode.isEmpty() ? 1 : unicode.length();
                for (int i = 0; i < length; i++) {
                    characterMap.add(position);
                }
            }
            if (start >= characterMap.size()) {
                return null;
            }
            int safeEnd = Math.min(end, characterMap.size());
            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxX = 0;
            float maxY = 0;
            for (int i = start; i < safeEnd; i++) {
                TextPosition position = characterMap.get(i);
                float x = position.getXDirAdj();
                float y = position.getYDirAdj() - position.getHeightDir();
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x + position.getWidthDirAdj());
                maxY = Math.max(maxY, y + position.getHeightDir());
            }
            return minX == Float.MAX_VALUE ? null : new Box(minX, minY, maxX - minX, maxY - minY);
        }
    }

    private record Box(float x, float y, float width, float height) {
    }
}

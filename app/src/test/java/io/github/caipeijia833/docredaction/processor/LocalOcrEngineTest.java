/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.caipeijia833.docredaction.rules.RuleEngine;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

class LocalOcrEngineTest {
    @TempDir
    Path temp;

    @Test
    void mapsSensitiveTextBackToOcrCoordinates() {
        String tsv = "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n"
                + "5\t1\t1\t1\t1\t1\t10\t20\t30\t18\t95\t手机\n"
                + "5\t1\t1\t1\t1\t2\t45\t20\t110\t18\t96\t13800138000\n";
        ProcessReport report = new ProcessReport();

        var boxes = LocalOcrEngine.detectBoxes(tsv, RuleEngine.createDefault(), report);

        assertFalse(boxes.isEmpty());
        assertEquals(1, report.counts().get("CN_MOBILE_PHONE"));
        assertTrue(boxes.stream().anyMatch(box -> box.left() == 45 && box.width() == 110));
    }

    @Test
    void scannedPdfFailsClosedWhenChineseOcrIsUnavailable() throws Exception {
        Path input = temp.resolve("scan.pdf");
        Path output = temp.resolve("scan-redacted.pdf");
        BufferedImage image = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 400, 200);
        graphics.setColor(Color.BLACK);
        graphics.drawString("13800138000", 30, 80);
        graphics.dispose();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(400, 200));
            document.addPage(page);
            var embedded = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(embedded, 0, 0, 400, 200);
            }
            document.save(input.toFile());
        }
        String previous = System.getProperty("docredaction.ocr.executable");
        System.setProperty("docredaction.ocr.executable", temp.resolve("missing-tesseract").toString());
        try {
            IOException error = assertThrows(IOException.class,
                    () -> new PdfProcessor().process(input, output, RuleEngine.createDefault()));
            assertTrue(error.getMessage().contains("失败关闭策略") || error.getMessage().contains("Tesseract"));
            assertFalse(java.nio.file.Files.exists(output));
        } finally {
            if (previous == null) {
                System.clearProperty("docredaction.ocr.executable");
            } else {
                System.setProperty("docredaction.ocr.executable", previous);
            }
        }
    }

    @Test
    @Tag("native")
    void bundledTesseractDetectsAndRedactsMobileNumber() throws Exception {
        assertEquals("true", System.getProperty("docredaction.ocr.integration"),
                "The verified Windows build must execute the bundled OCR integration test");
        BufferedImage image = new BufferedImage(1600, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font("Microsoft YaHei", Font.PLAIN, 72));
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.drawString("姓名：张三  手机：13800138000", 30, 160);
        graphics.dispose();
        int darkPixelsBefore = countDarkPixels(image);

        LocalOcrEngine engine = new LocalOcrEngine();
        assertTrue(engine.capability().available(), engine.capability().message());
        assertEquals("5.5.2", engine.capability().version());
        assertEquals("chi_sim+eng", engine.capability().languages());
        ProcessReport report = new ProcessReport();
        engine.redact(image, RuleEngine.createDefault(), report, temp.resolve("ocr-work"), "mobile");

        assertEquals(1, report.counts().get("CN_MOBILE_PHONE"));
        assertTrue(countDarkPixels(image) > darkPixelsBefore + 5_000,
                "OCR coordinates should produce a substantial opaque mask");
    }

    @Test
    @Tag("native")
    void imageResidualScannerUsesOcrInsteadOfUnsupportedDocumentInspector() throws Exception {
        Path original = temp.resolve("original.png");
        Path output = temp.resolve("output.png");
        BufferedImage image = new BufferedImage(300, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLACK);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        javax.imageio.ImageIO.write(image, "png", original.toFile());
        javax.imageio.ImageIO.write(image, "png", output.toFile());
        ProcessReport report = new ProcessReport();

        ResidualScanner.verify(original, output, RuleEngine.createDefault(), report);

        assertTrue(report.warnings().stream().anyMatch(item -> item.contains("图像") && item.contains("残留复扫")));
    }

    private static int countDarkPixels(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                if (((rgb >> 16) & 0xff) < 16 && ((rgb >> 8) & 0xff) < 16 && (rgb & 0xff) < 16) {
                    count++;
                }
            }
        }
        return count;
    }
}

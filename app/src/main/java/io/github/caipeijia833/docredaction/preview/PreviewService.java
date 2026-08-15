/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.preview;

import io.github.caipeijia833.docredaction.ofd.OfdBridgeRunner;
import io.github.caipeijia833.docredaction.processor.OoxmlPackageProcessor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.Locale;

public final class PreviewService {
    private static final int MAX_TEXT_CHARS = 200_000;

    public PreviewResult preview(Path file, int page) throws Exception {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) {
            return previewPdf(file, page);
        }
        if (name.endsWith(".docx")) {
            return html("Word本地文档预览", extractDocx(file));
        }
        if (name.endsWith(".xlsx")) {
            return html("Excel本地文档预览", extractXlsx(file));
        }
        if (name.endsWith(".pptx")) {
            return html("PowerPoint本地文档预览", extractPptx(file));
        }
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".bmp")) {
            return previewImage(file);
        }
        if (name.endsWith(".ofd")) {
            return previewOfd(file, page);
        }
        throw new IOException("当前格式暂不支持本地结果预览");
    }

    private static PreviewResult previewImage(Path file) throws IOException {
        BufferedImage source = ImageIO.read(file.toFile());
        if (source == null) {
            throw new IOException("无法解码预览图像");
        }
        double scale = Math.min(1d, Math.min(1800d / source.getWidth(), 1400d / source.getHeight()));
        BufferedImage image = source;
        if (scale < 1d) {
            image = new BufferedImage(Math.max(1, (int) Math.round(source.getWidth() * scale)),
                    Math.max(1, (int) Math.round(source.getHeight() * scale)), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(source, 0, 0, image.getWidth(), image.getHeight(), null);
            } finally {
                graphics.dispose();
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("无法生成图像预览");
        }
        return new PreviewResult("image/png", output.toByteArray());
    }

    private static PreviewResult previewOfd(Path file, int page) throws Exception {
        if (page < 0) {
            throw new IllegalArgumentException("OFD页码超出范围");
        }
        Path work = Files.createTempDirectory("doc-redaction-ofd-preview-");
        try {
            Path image = new OfdBridgeRunner().renderPage(file, page, work.resolve("page.png"), 4.5d);
            return previewImage(image);
        } finally {
            try (var paths = Files.walk(work)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static PreviewResult previewPdf(Path file, int page) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            if (page < 0 || page >= document.getNumberOfPages()) {
                throw new IllegalArgumentException("PDF页码超出范围");
            }
            BufferedImage image = new PDFRenderer(document).renderImageWithDPI(page, 110, ImageType.RGB);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) {
                throw new IOException("无法生成PDF预览图");
            }
            return new PreviewResult("image/png", output.toByteArray());
        }
    }

    private static String extractDocx(Path file) throws IOException {
        return extractOoxml(file, OoxmlPackageProcessor.Kind.DOCX);
    }

    private static String extractXlsx(Path file) throws Exception {
        return extractOoxml(file, OoxmlPackageProcessor.Kind.XLSX);
    }

    private static String extractPptx(Path file) throws IOException {
        return extractOoxml(file, OoxmlPackageProcessor.Kind.PPTX);
    }

    private static String extractOoxml(Path file, OoxmlPackageProcessor.Kind kind) throws IOException {
        StringBuilder text = new StringBuilder();
        try {
            OoxmlPackageProcessor.scanText(file, kind, (location, value) -> {
                if (!value.isBlank()) text.append('【').append(location).append("】\n").append(value).append('\n');
                return text.length() < MAX_TEXT_CHARS;
            });
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("无法生成Office流式文本预览", ex);
        }
        return limited(text.toString());
    }

    private static PreviewResult html(String title, String text) {
        String page = "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + escape(title) + "</title><style>body{margin:0;background:#eef2f7;color:#1d2a40;"
                + "font:14px/1.7 system-ui,-apple-system,'Microsoft YaHei',sans-serif}main{max-width:1050px;margin:24px auto;"
                + "background:white;padding:24px;border-radius:10px;box-shadow:0 8px 30px #2233}h1{font-size:18px;margin-top:0}"
                + "p{color:#66758c}pre{white-space:pre-wrap;word-break:break-word;background:#f8fafc;padding:18px;border:1px solid #dfe6ef;"
                + "border-radius:8px}</style></head><body><main><h1>" + escape(title) + "</h1>"
                + "<p>此页面由本地服务从当前任务文档提取，可能是处理前原文或处理后结果；仅用于快速复核，版式验收仍应使用原办公软件打开相应文件。</p><pre>"
                + escape(text) + "</pre></main></body></html>";
        return new PreviewResult("text/html; charset=utf-8", page.getBytes(StandardCharsets.UTF_8));
    }

    private static String limited(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_TEXT_CHARS ? value : value.substring(0, MAX_TEXT_CHARS) + "\n…预览已截断…";
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}

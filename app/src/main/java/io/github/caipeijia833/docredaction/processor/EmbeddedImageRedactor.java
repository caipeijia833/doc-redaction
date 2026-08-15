/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import io.github.caipeijia833.docredaction.rules.RuleEngine;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;

final class EmbeddedImageRedactor implements AutoCloseable {
    private final RuleEngine rules;
    private final ProcessReport report;
    private final Path workDirectory;
    private LocalOcrEngine ocr;
    private int imageCount;

    EmbeddedImageRedactor(Path output, RuleEngine rules, ProcessReport report) {
        this.rules = rules;
        this.report = report;
        this.workDirectory = output.getParent().resolve("office-image-ocr-" + UUID.randomUUID()).normalize();
    }

    byte[] redact(byte[] data, String suggestedExtension) throws IOException {
        if (data == null || data.length == 0) {
            throw new IOException("办公文档包含空的嵌入图片");
        }
        String format = normalizeFormat(suggestedExtension);
        if (!format.equals("png") && !format.equals("jpg") && !format.equals("bmp") && !format.equals("gif")) {
            throw new IOException("嵌入图片格式" + format + "无法安全OCR，任务按失败关闭策略停止");
        }
        if (ocr == null) {
            ocr = new LocalOcrEngine();
        }
        if (!ocr.capability().available()) {
            throw new IOException("办公文档包含图片；" + ocr.capability().message());
        }
        ImageResourceGuard.validate(data);
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(data));
        if (source == null) {
            throw new IOException("无法解码办公文档中的嵌入图片");
        }
        BufferedImage image = format.equals("jpg") || format.equals("bmp") ? toRgb(source) : source;
        ocr.redact(image, rules, report, workDirectory, "image-" + (++imageCount));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, output)) {
            throw new IOException("无法重新编码办公文档中的嵌入图片");
        }
        return output.toByteArray();
    }

    boolean processedImages() {
        return imageCount > 0;
    }

    @Override
    public void close() {
        if (!Files.exists(workDirectory)) {
            return;
        }
        try (var paths = Files.walk(workDirectory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Job-level cleanup retries locked temporary files.
        }
    }

    private static String normalizeFormat(String extension) {
        String value = extension == null ? "" : extension.trim().toLowerCase(Locale.ROOT);
        if (value.equals("jpeg") || value.equals("jpe")) {
            return "jpg";
        }
        return value;
    }

    private static BufferedImage toRgb(BufferedImage source) {
        BufferedImage target = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, target.getWidth(), target.getHeight());
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }
}

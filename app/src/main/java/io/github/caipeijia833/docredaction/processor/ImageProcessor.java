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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class ImageProcessor implements DocumentProcessor {
    @Override
    public Set<String> extensions() {
        return Set.of("png", "jpg", "jpeg", "bmp");
    }

    @Override
    public ProcessReport process(Path input, Path output, RuleEngine ruleEngine) throws Exception {
        LocalOcrEngine ocr = new LocalOcrEngine();
        if (!ocr.capability().available()) {
            throw new IOException(ocr.capability().message());
        }
        ImageResourceGuard.inspect(input);
        BufferedImage source = ImageIO.read(input.toFile());
        if (source == null) {
            throw new IOException("无法解码图像文件");
        }
        String extension = extension(input);
        BufferedImage image = requiresRgb(extension) ? toRgb(source) : source;
        Path work = output.getParent().resolve("image-ocr-" + UUID.randomUUID()).normalize();
        ProcessReport report = new ProcessReport();
        try {
            ocr.redact(image, ruleEngine, report, work, "image");
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            String format = extension.equals("jpeg") ? "jpg" : extension;
            if (!ImageIO.write(image, format, output.toFile())) {
                throw new IOException("无法编码脱敏图像");
            }
            report.unitProcessed();
            report.warning("图像通过本地Tesseract OCR定位并采用不可逆黑色遮挡输出；原图元数据不进入结果文件。");
            return report;
        } finally {
            deleteTree(work);
        }
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

    private static boolean requiresRgb(String extension) {
        return extension.equals("jpg") || extension.equals("jpeg") || extension.equals("bmp");
    }

    private static String extension(Path input) {
        String name = input.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "png" : name.substring(dot + 1);
    }

    private static void deleteTree(Path target) {
        if (!Files.exists(target)) {
            return;
        }
        try (var paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Job-level cleanup retries locked temporary files.
        }
    }
}

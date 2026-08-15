/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import io.github.caipeijia833.docredaction.ofd.OfdBridgeRunner;
import io.github.caipeijia833.docredaction.rules.RuleEngine;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class OfdProcessor implements DocumentProcessor {
    private static final double PIXELS_PER_MILLIMETRE = 6.0d;

    @Override
    public Set<String> extensions() {
        return Set.of("ofd");
    }

    @Override
    public ProcessReport process(Path input, Path output, RuleEngine ruleEngine) throws Exception {
        LocalOcrEngine ocr = new LocalOcrEngine();
        if (!ocr.capability().available()) {
            throw new IOException(ocr.capability().message());
        }
        Path work = output.getParent().resolve("ofd-ocr-" + UUID.randomUUID()).normalize();
        Path pages = work.resolve("pages");
        Files.createDirectories(pages);
        ProcessReport report = new ProcessReport();
        try {
            List<Path> pageImages;
            List<OfdBridgeRunner.RasterPage> rasterPages = new java.util.ArrayList<>();
            OfdBridgeRunner bridge = new OfdBridgeRunner();
            pageImages = bridge.renderAll(input, pages, PIXELS_PER_MILLIMETRE);
            if (pageImages.isEmpty()) {
                throw new IOException("OFD文件未包含可渲染页面");
            }
            for (int index = 0; index < pageImages.size(); index++) {
                Path page = pageImages.get(index);
                BufferedImage image = ImageIO.read(page.toFile());
                if (image == null) {
                    throw new IOException("无法解码OFD渲染页面");
                }
                rasterPages.add(new OfdBridgeRunner.RasterPage(page,
                        image.getWidth() / PIXELS_PER_MILLIMETRE,
                        image.getHeight() / PIXELS_PER_MILLIMETRE));
                ocr.redact(image, ruleEngine, report, work.resolve("ocr"), "page-" + (index + 1));
                if (!ImageIO.write(image, "png", page.toFile())) {
                    throw new IOException("无法写入OFD脱敏页面");
                }
                report.unitProcessed();
            }
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            bridge.buildRasterOfdPages(output, rasterPages);
            report.warning("OFD采用逐页栅格化、离线OCR定位和不可逆遮挡后重新生成；原文本层、附件及活动内容不进入结果文件。");
            report.warning("重新生成会使原OFD数字签名失效，结果文件必须作为新文档重新签章。");
            return report;
        } finally {
            deleteTree(work);
        }
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

/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import io.github.caipeijia833.docredaction.ofd.OfdBridgeRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreviewServiceTest {
    @TempDir
    Path temp;

    @Test
    void rendersOfdWithoutItextRuntime() throws Exception {
        Path page = temp.resolve("page.png");
        BufferedImage source = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = source.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.drawString("synthetic OFD page", 40, 80);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(source, "png", page.toFile());
        Path ofd = temp.resolve("synthetic.ofd");
        new OfdBridgeRunner().buildRasterOfd(ofd, List.of(page), 210d);

        PreviewResult preview = new PreviewService().preview(ofd, 0);

        assertEquals("image/png", preview.contentType());
        assertTrue(preview.bytes().length > 100);
        assertTrue(Files.size(ofd) > 0);
    }

    @Test
    void preservesPerPagePhysicalAspectInsteadOfForcingA4Width() throws Exception {
        Path page = temp.resolve("wide-page.png");
        BufferedImage source = new BufferedImage(600, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = source.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
        } finally {
            graphics.dispose();
        }
        ImageIO.write(source, "png", page.toFile());
        Path ofd = temp.resolve("wide.ofd");
        OfdBridgeRunner bridge = new OfdBridgeRunner();
        bridge.buildRasterOfdPages(ofd,
                List.of(new OfdBridgeRunner.RasterPage(page, 100d, 50d)));

        Path rendered = temp.resolve("wide-rendered.png");
        bridge.renderPage(ofd, 0, rendered, 2d);
        BufferedImage roundTrip = ImageIO.read(rendered.toFile());

        assertTrue(roundTrip.getWidth() >= 190 && roundTrip.getWidth() <= 210);
        assertTrue(roundTrip.getHeight() >= 90 && roundTrip.getHeight() <= 110);
        assertEquals(2d, (double) roundTrip.getWidth() / roundTrip.getHeight(), 0.05d);
    }
}

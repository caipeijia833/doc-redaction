/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImageResourceGuardTest {
    @TempDir
    Path temp;

    @Test
    void readsDimensionsWithoutDecodingAndEnforcesPixelLimit() throws Exception {
        Path image = temp.resolve("header.png");
        ImageIO.write(new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB), "png", image.toFile());
        var dimensions = ImageResourceGuard.inspect(image);
        assertEquals(20, dimensions.width());
        assertEquals(10, dimensions.height());
        assertEquals(200L, dimensions.pixels());

        String previous = System.getProperty("docredaction.image.maxPixels");
        try {
            System.setProperty("docredaction.image.maxPixels", "199");
            assertThrows(IOException.class, () -> ImageResourceGuard.inspect(image));
        } finally {
            if (previous == null) {
                System.clearProperty("docredaction.image.maxPixels");
            } else {
                System.setProperty("docredaction.image.maxPixels", previous);
            }
        }
    }
}

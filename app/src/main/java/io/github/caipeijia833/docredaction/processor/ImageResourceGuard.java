/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Iterator;

/** Reads image headers without decoding pixels and rejects decompression bombs. */
final class ImageResourceGuard {
    static final long DEFAULT_MAX_PIXELS = 100_000_000L;
    static final int DEFAULT_MAX_DIMENSION = 32_768;

    private ImageResourceGuard() {
    }

    static Dimensions inspect(Path input) throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(input.toFile())) {
            return inspect(stream);
        }
    }

    static Dimensions validate(byte[] input) throws IOException {
        if (input == null || input.length == 0) {
            throw new IOException("无法读取空图像");
        }
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            return inspect(stream);
        }
    }

    private static Dimensions inspect(ImageInputStream stream) throws IOException {
        long maximumPixels = positiveLongProperty("docredaction.image.maxPixels", DEFAULT_MAX_PIXELS);
        int maximumDimension = positiveIntProperty("docredaction.image.maxDimension", DEFAULT_MAX_DIMENSION);
        if (stream == null) {
            throw new IOException("无法读取图像文件头");
        }
        Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
        if (!readers.hasNext()) {
            throw new IOException("无法识别图像编码格式");
        }
        ImageReader reader = readers.next();
        try {
            reader.setInput(stream, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width <= 0 || height <= 0) {
                throw new IOException("图像尺寸无效");
            }
            long pixels;
            try {
                pixels = Math.multiplyExact((long) width, (long) height);
            } catch (ArithmeticException ex) {
                throw new IOException("图像像素数量溢出", ex);
            }
            if (width > maximumDimension || height > maximumDimension || pixels > maximumPixels) {
                throw new IOException("图像解码尺寸超过安全上限：" + width + "x" + height
                        + "，最大像素数=" + maximumPixels);
            }
            return new Dimensions(width, height, pixels, reader.getFormatName());
        } finally {
            reader.dispose();
        }
    }

    private static long positiveLongProperty(String name, long fallback) {
        try {
            long value = Long.parseLong(System.getProperty(name, Long.toString(fallback)));
            return value > 0 ? value : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static int positiveIntProperty(String name, int fallback) {
        try {
            int value = Integer.parseInt(System.getProperty(name, Integer.toString(fallback)));
            return value > 0 ? value : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    record Dimensions(int width, int height, long pixels, String format) {
    }
}

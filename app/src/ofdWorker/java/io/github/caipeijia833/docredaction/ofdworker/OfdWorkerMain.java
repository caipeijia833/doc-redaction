/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.ofdworker;

import org.ofdrw.converter.export.ImageExporter;
import org.ofdrw.layout.OFDDoc;
import org.ofdrw.layout.PageLayout;
import org.ofdrw.layout.VirtualPage;
import org.ofdrw.layout.element.Img;
import org.ofdrw.layout.element.Position;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Minimal OFDRW entry point. It has an isolated PDFBox 2 classpath and no iText dependency. */
public final class OfdWorkerMain {
    private static final int MAGIC = 0x4f464457;
    private static final int VERSION = 2;
    private static final int MAX_PAGES = 10_000;
    private static final int MAX_STRING_BYTES = 64 * 1024;

    private OfdWorkerMain() {
    }

    public static void main(String[] args) {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                    new FileInputStream(FileDescriptor.in)));
                DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                    new FileOutputStream(FileDescriptor.out)))) {
            try {
                if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                    throw new IOException("OFD请求协议无效");
                }
                String operation = readString(input);
                var classLoader = OfdWorkerMain.class.getClassLoader();
                var colorShim = classLoader.getResource("org/ofdrw/converter/ColorConvert.class");
                var commonShim = classLoader.getResource("org/ofdrw/converter/utils/CommonUtil.class");
                var fontShim = classLoader.getResource("org/ofdrw/converter/FontLoader.class");
                if (colorShim == null || commonShim == null || fontShim == null
                        || !colorShim.toString().contains("doc-redaction-ofd-worker")
                        || !commonShim.toString().contains("doc-redaction-ofd-worker")
                        || !fontShim.toString().contains("doc-redaction-ofd-worker")) {
                    throw new IOException("OFD无iText兼容层未优先加载");
                }
                ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
                try (DataOutputStream payload = new DataOutputStream(payloadBytes)) {
                    switch (operation) {
                        case "RENDER_ALL" -> renderAll(input, payload);
                        case "RENDER_PAGE" -> renderPage(input, payload);
                        case "BUILD" -> build(input, payload);
                        default -> throw new IOException("OFD请求操作无效");
                    }
                    payload.flush();
                }
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeBoolean(true);
                payloadBytes.writeTo(output);
            } catch (Throwable ex) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeBoolean(false);
                writeString(output, safeMessage(ex));
            }
            output.flush();
        } catch (Throwable ignored) {
            // The parent process treats a missing response as a hard failure.
        }
    }

    private static void renderAll(DataInputStream input, DataOutputStream output) throws Exception {
        Path source = existingFile(readPath(input));
        Path directory = directory(readPath(input));
        double scale = checkedScale(input.readDouble());
        List<Path> pages;
        try (ImageExporter exporter = new ImageExporter(source, directory, "png", scale)) {
            exporter.export();
            pages = List.copyOf(exporter.getImgFilePaths());
        }
        if (pages.size() > MAX_PAGES) {
            throw new IOException("OFD页数超过安全上限");
        }
        output.writeInt(pages.size());
        for (Path page : pages) {
            Path normalized = page.toAbsolutePath().normalize();
            if (!normalized.startsWith(directory) || !Files.isRegularFile(normalized)) {
                throw new IOException("OFD渲染结果路径无效");
            }
            writeString(output, directory.relativize(normalized).toString());
        }
    }

    private static void renderPage(DataInputStream input, DataOutputStream output) throws Exception {
        Path source = existingFile(readPath(input));
        int page = input.readInt();
        Path requestedOutput = readPath(input);
        double scale = checkedScale(input.readDouble());
        if (page < 0) {
            throw new IOException("OFD页码无效");
        }
        Path directory = directory(requestedOutput.getParent());
        List<Path> pages;
        try (ImageExporter exporter = new ImageExporter(source, directory, "png", scale)) {
            exporter.export(page);
            pages = List.copyOf(exporter.getImgFilePaths());
        }
        if (pages.isEmpty() || !Files.isRegularFile(pages.getFirst())) {
            output.writeBoolean(false);
            return;
        }
        Files.move(pages.getFirst(), requestedOutput, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        output.writeBoolean(true);
    }

    private static void build(DataInputStream input, DataOutputStream output) throws Exception {
        Path target = readPath(input);
        int count = input.readInt();
        if (count <= 0 || count > MAX_PAGES) {
            throw new IOException("OFD输出页数无效");
        }
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        try (OFDDoc document = new OFDDoc(target)) {
            for (int i = 0; i < count; i++) {
                Path page = existingFile(readPath(input));
                double width = checkedDimension(input.readDouble());
                double height = checkedDimension(input.readDouble());
                BufferedImage image = ImageIO.read(page.toFile());
                if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                    throw new IOException("无法解码OFD输出页面");
                }
                PageLayout layout = new PageLayout(width, height).setMargin(0d);
                VirtualPage virtualPage = new VirtualPage(layout);
                virtualPage.add(new Img(width, height, page)
                        .setPosition(Position.Absolute).setXY(0d, 0d)
                        .setMargin(0d).setPadding(0d).setBorder(0d));
                document.addVPage(virtualPage);
            }
        }
        output.writeBoolean(Files.isRegularFile(target));
    }

    private static Path readPath(DataInputStream input) throws IOException {
        return Path.of(readString(input)).toAbsolutePath().normalize();
    }

    private static Path existingFile(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("OFD输入文件不存在");
        }
        return path;
    }

    private static Path directory(Path path) throws IOException {
        if (path == null) {
            throw new IOException("OFD输出目录无效");
        }
        Path normalized = path.toAbsolutePath().normalize();
        Files.createDirectories(normalized);
        return normalized;
    }

    private static double checkedScale(double value) throws IOException {
        if (!Double.isFinite(value) || value < 1d || value > 12d) {
            throw new IOException("OFD渲染比例无效");
        }
        return value;
    }

    private static double checkedDimension(double value) throws IOException {
        if (!Double.isFinite(value) || value < 10d || value > 1_000d) {
            throw new IOException("OFD页面宽度无效");
        }
        return value;
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("OFD协议字符串长度无效");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("OFD协议字符串不完整");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("OFD协议字符串超过安全上限");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String safeMessage(Throwable ex) {
        if (ex instanceof OutOfMemoryError) {
            return "OFD处理内存不足";
        }
        if (ex instanceof IOException && ex.getMessage() != null && !ex.getMessage().isBlank()) {
            return ex.getMessage().replaceAll("[\\r\\n]", " ");
        }
        String detail = ex.getMessage() == null ? "" : ex.getMessage().replaceAll("[\\r\\n]", " ");
        if (detail.length() > 512) {
            detail = detail.substring(0, 512);
        }
        StringBuilder locations = new StringBuilder();
        for (int i = 0; i < Math.min(6, ex.getStackTrace().length); i++) {
            StackTraceElement frame = ex.getStackTrace()[i];
            locations.append(i == 0 ? "，位置：" : " <- ")
                    .append(frame.getClassName()).append('.').append(frame.getMethodName());
        }
        return "OFD隔离进程处理失败（" + ex.getClass().getSimpleName()
                + (detail.isBlank() ? "" : "：" + detail) + locations + "）";
    }
}

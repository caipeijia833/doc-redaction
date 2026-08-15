/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.ofd;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bridges the application to a separately class-loaded OFDRW/PDFBox 2 worker. */
public final class OfdBridgeRunner {
    private static final int MAGIC = 0x4f464457;
    private static final int VERSION = 2;
    private static final int MAX_PAGES = 10_000;
    private static final int MAX_STRING_BYTES = 64 * 1024;
    private static final String MAIN_CLASS = "io.github.caipeijia833.docredaction.ofdworker.OfdWorkerMain";

    public List<Path> renderAll(Path input, Path outputDirectory, double pixelsPerMillimetre)
            throws IOException {
        Files.createDirectories(outputDirectory);
        return invoke("RENDER_ALL", output -> {
            writePath(output, input);
            writePath(output, outputDirectory);
            output.writeDouble(pixelsPerMillimetre);
        }, response -> {
            int count = checkedCount(response.readInt());
            List<Path> paths = new ArrayList<>(count);
            Path root = outputDirectory.toAbsolutePath().normalize();
            for (int i = 0; i < count; i++) {
                Path path = root.resolve(readString(response)).normalize();
                if (!path.startsWith(root) || !Files.isRegularFile(path)) {
                    throw new IOException("OFD工作进程返回了无效页面路径");
                }
                paths.add(path);
            }
            return List.copyOf(paths);
        });
    }

    public Path renderPage(Path input, int page, Path output, double pixelsPerMillimetre)
            throws IOException {
        if (page < 0) {
            throw new IllegalArgumentException("OFD页码超出范围");
        }
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        return invoke("RENDER_PAGE", request -> {
            writePath(request, input);
            request.writeInt(page);
            writePath(request, output);
            request.writeDouble(pixelsPerMillimetre);
        }, response -> {
            if (!response.readBoolean() || !Files.isRegularFile(output)) {
                throw new IllegalArgumentException("OFD页码超出范围");
            }
            return output;
        });
    }

    public void buildRasterOfd(Path output, List<Path> pageImages, double widthMillimetres)
            throws IOException {
        if (pageImages.isEmpty() || pageImages.size() > MAX_PAGES) {
            throw new IOException("OFD输出页数无效");
        }
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        invoke("BUILD", request -> {
            writePath(request, output);
            request.writeInt(pageImages.size());
            for (Path page : pageImages) {
                writePath(request, page);
                var image = javax.imageio.ImageIO.read(page.toFile());
                if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                    throw new IOException("Unable to decode OFD output page image");
                }
                request.writeDouble(checkedDimension(widthMillimetres));
                request.writeDouble(checkedDimension(
                        widthMillimetres * image.getHeight() / image.getWidth()));
            }
        }, response -> {
            if (!response.readBoolean() || !Files.isRegularFile(output)) {
                throw new IOException("OFD工作进程未生成结果文件");
            }
            return null;
        });
    }

    public void buildRasterOfdPages(Path output, List<RasterPage> pages) throws IOException {
        if (pages.isEmpty() || pages.size() > MAX_PAGES) {
            throw new IOException("OFD output page count is outside the safety limit");
        }
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        invoke("BUILD", request -> {
            writePath(request, output);
            request.writeInt(pages.size());
            for (RasterPage page : pages) {
                writePath(request, page.image());
                request.writeDouble(checkedDimension(page.widthMillimetres()));
                request.writeDouble(checkedDimension(page.heightMillimetres()));
            }
        }, response -> {
            if (!response.readBoolean() || !Files.isRegularFile(output)) {
                throw new IOException("OFD worker did not generate an output file");
            }
            return null;
        });
    }

    private static double checkedDimension(double value) throws IOException {
        if (!Double.isFinite(value) || value < 10d || value > 1_000d) {
            throw new IOException("OFD page dimension is outside the 10-1000 mm safety range");
        }
        return value;
    }

    public record RasterPage(Path image, double widthMillimetres, double heightMillimetres) {
    }

    public static String configuredClasspath() {
        return System.getProperty("docredaction.ofd.classpath", "").trim();
    }

    private <T> T invoke(String operation, RequestWriter requestWriter, ResponseReader<T> responseReader)
            throws IOException {
        String classpath = configuredClasspath();
        if (classpath.isBlank()) {
            throw new IOException("OFD隔离组件未配置，请使用正式离线包或设置docredaction.ofd.classpath");
        }
        Process process = new ProcessBuilder(
                javaExecutable().toString(), "-Xms64m", "-Xmx2048m", "-XX:+ExitOnOutOfMemoryError",
                "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-cp", classpath, MAIN_CLASS)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        FutureTask<T> response = new FutureTask<>(() -> readResponse(process.getInputStream(), responseReader));
        Thread reader = Thread.startVirtualThread(response);
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(process.getOutputStream()))) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            writeString(output, operation);
            requestWriter.write(output);
            output.flush();
        } catch (IOException ex) {
            terminate(process);
            throw new IOException("无法向OFD隔离进程发送任务", ex);
        }
        try {
            if (!process.waitFor(30, TimeUnit.MINUTES)) {
                terminate(process);
                response.cancel(true);
                throw new IOException("OFD处理超过30分钟，隔离进程已终止");
            }
            T result = response.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw new IOException("OFD隔离进程异常退出（代码" + process.exitValue() + "）");
            }
            return result;
        } catch (InterruptedException ex) {
            terminate(process);
            response.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("OFD处理已取消", ex);
        } catch (ExecutionException ex) {
            terminate(process);
            Throwable cause = ex.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof IllegalArgumentException argument) {
                throw argument;
            }
            throw new IOException("无法读取OFD隔离进程结果", cause);
        } catch (TimeoutException ex) {
            terminate(process);
            response.cancel(true);
            throw new IOException("OFD隔离进程未返回完整结果", ex);
        } finally {
            if (process.isAlive()) {
                terminate(process);
            }
            reader.interrupt();
        }
    }

    private static <T> T readResponse(InputStream source, ResponseReader<T> reader) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(source))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                throw new IOException("OFD隔离进程响应协议无效");
            }
            if (!input.readBoolean()) {
                throw new IOException(readString(input));
            }
            return reader.read(input);
        }
    }

    private static int checkedCount(int value) throws IOException {
        if (value < 0 || value > MAX_PAGES) {
            throw new IOException("OFD页面数量超过安全上限");
        }
        return value;
    }

    private static void writePath(DataOutputStream output, Path value) throws IOException {
        writeString(output, value.toAbsolutePath().normalize().toString());
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("OFD协议字符串超过安全上限");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
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
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Path javaExecutable() {
        String name = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name).toAbsolutePath().normalize();
    }

    private static void terminate(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface RequestWriter {
        void write(DataOutputStream output) throws IOException;
    }

    @FunctionalInterface
    private interface ResponseReader<T> {
        T read(DataInputStream input) throws IOException;
    }
}

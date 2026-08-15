/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Locates and invokes the fixed local-only native media toolchain. */
final class MediaToolchain {
    private static final int MAX_CAPTURE_BYTES = 8 * 1024 * 1024;

    private final Path ffmpeg;
    private final Path ffprobe;
    private final Path whisper;
    private final Path whisperModel;
    private final Path faceModel;
    private final Path plateModel;
    private final long timeoutSeconds;

    MediaToolchain() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        this.ffmpeg = findExecutable("docredaction.media.ffmpeg", windows ? "ffmpeg.exe" : "ffmpeg",
                Path.of("media", "ffmpeg", "bin", windows ? "ffmpeg.exe" : "ffmpeg"),
                Path.of("media", "bin", windows ? "ffmpeg.exe" : "ffmpeg"));
        this.ffprobe = findExecutable("docredaction.media.ffprobe", windows ? "ffprobe.exe" : "ffprobe",
                Path.of("media", "ffmpeg", "bin", windows ? "ffprobe.exe" : "ffprobe"),
                Path.of("media", "bin", windows ? "ffprobe.exe" : "ffprobe"));
        this.whisper = findExecutable("docredaction.media.whisper", windows ? "whisper-cli.exe" : "whisper-cli",
                Path.of("media", "whisper", windows ? "whisper-cli.exe" : "whisper-cli"),
                Path.of("media", "bin", windows ? "whisper-cli.exe" : "whisper-cli"));
        this.whisperModel = findFile("docredaction.media.whisperModel",
                Path.of("media", "models", "ggml-base-q5_1.bin"));
        this.faceModel = findFile("docredaction.media.faceModel",
                Path.of("media", "models", "face_detection_yunet_2023mar.onnx"));
        this.plateModel = findFile("docredaction.media.plateModel",
                Path.of("media", "models", "license_plate_detection_lpd_yunet_2023mar.onnx"));
        this.timeoutSeconds = boundedLongProperty("docredaction.media.nativeTimeoutSeconds",
                7_200L, 10L, 24L * 60L * 60L);
    }

    Capability capability() {
        List<String> problems = new ArrayList<>();
        String ffmpegVersion = "";
        String whisperVersion = "";
        if (ffmpeg == null || ffprobe == null) {
            problems.add("未找到本地FFmpeg/ffprobe运行时");
        } else {
            try {
                String version = runText(List.of(ffmpeg.toString(), "-version"), 20L);
                String configuration = version.lines()
                        .filter(line -> line.startsWith("configuration:"))
                        .findFirst().orElse("");
                if (configuration.contains("--enable-gpl") || configuration.contains("--enable-nonfree")) {
                    problems.add("FFmpeg构建包含GPL或nonfree组件，已按许可策略拒绝");
                } else if (!configuration.contains("--enable-shared")) {
                    problems.add("FFmpeg不是已批准的LGPL共享库构建");
                }
                ffmpegVersion = version.lines().findFirst().orElse("");
                runText(List.of(ffprobe.toString(), "-version"), 20L);
            } catch (IOException ex) {
                problems.add("FFmpeg能力检查失败");
            }
        }
        if (whisper == null || whisperModel == null) {
            problems.add("未找到本地whisper.cpp或多语言量化模型");
        } else {
            try {
                whisperVersion = runText(List.of(whisper.toString(), "--version"), 20L)
                        .lines().findFirst().orElse("");
            } catch (IOException ex) {
                problems.add("whisper.cpp能力检查失败");
            }
        }
        if (faceModel == null) {
            problems.add("未找到YuNet人脸检测模型");
        }
        if (plateModel == null) {
            problems.add("未找到中国车牌检测模型");
        }
        return new Capability(problems.isEmpty(), ffmpegVersion, whisperVersion,
                faceModel != null, plateModel != null, List.copyOf(problems));
    }

    Path ffmpeg() throws IOException {
        return require(ffmpeg, "未找到本地FFmpeg运行时");
    }

    Path ffprobe() throws IOException {
        return require(ffprobe, "未找到本地ffprobe运行时");
    }

    Path whisper() throws IOException {
        return require(whisper, "未找到本地whisper.cpp运行时");
    }

    Path whisperModel() throws IOException {
        return require(whisperModel, "未找到本地多语言ASR模型");
    }

    Path faceModel() throws IOException {
        return require(faceModel, "未找到YuNet人脸检测模型");
    }

    Path plateModel() throws IOException {
        return require(plateModel, "未找到中国车牌检测模型");
    }

    String run(List<String> command) throws IOException {
        return runText(command, timeoutSeconds);
    }

    String runText(List<String> command, long timeout) throws IOException {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("原生进程命令不能为空");
        }
        Process process = new ProcessBuilder(List.copyOf(command))
                .redirectErrorStream(false)
                .start();
        FutureTask<byte[]> stdout = new FutureTask<>(() -> readBounded(process.getInputStream()));
        FutureTask<byte[]> stderr = new FutureTask<>(() -> readBounded(process.getErrorStream()));
        Thread.startVirtualThread(stdout);
        Thread.startVirtualThread(stderr);
        try {
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                terminate(process);
                throw new IOException("本地音视频进程超过" + timeout + "秒，已终止");
            }
            byte[] out = stdout.get(5, TimeUnit.SECONDS);
            byte[] err = stderr.get(5, TimeUnit.SECONDS);
            String output = new String(out, StandardCharsets.UTF_8);
            String diagnostics = new String(err, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IOException("本地音视频进程失败（代码" + process.exitValue() + "）："
                        + safeDiagnostics(diagnostics));
            }
            return output.isBlank() ? diagnostics : output + (diagnostics.isBlank() ? "" : "\n" + diagnostics);
        } catch (InterruptedException ex) {
            terminate(process);
            Thread.currentThread().interrupt();
            throw new IOException("本地音视频处理已取消", ex);
        } catch (ExecutionException | TimeoutException ex) {
            terminate(process);
            throw new IOException("无法读取本地音视频进程输出", ex);
        } finally {
            if (process.isAlive()) {
                terminate(process);
            }
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_CAPTURE_BYTES) {
                    throw new IOException("本地音视频进程输出超过安全上限");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void terminate(Process process) {
        process.descendants().forEach(handle -> {
            try {
                handle.destroy();
            } catch (RuntimeException ignored) {
                // Continue terminating the remaining process tree.
            }
        });
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static String safeDiagnostics(String value) {
        String cleaned = value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (cleaned.isBlank()) {
            return "无诊断输出";
        }
        return cleaned.length() > 600 ? cleaned.substring(0, 600) : cleaned;
    }

    private static Path findExecutable(String property, String name, Path... candidates) {
        Path configured = configuredFile(property);
        if (configured != null) {
            return configured;
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String part : path.split(java.io.File.pathSeparator)) {
                if (!part.isBlank()) {
                    Path candidate = Path.of(part).resolve(name).toAbsolutePath().normalize();
                    if (Files.isRegularFile(candidate)) {
                        return candidate;
                    }
                }
            }
        }
        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalized)) {
                return normalized;
            }
        }
        return null;
    }

    private static Path findFile(String property, Path candidate) {
        Path configured = configuredFile(property);
        if (configured != null) {
            return configured;
        }
        Path normalized = candidate.toAbsolutePath().normalize();
        return Files.isRegularFile(normalized) ? normalized : null;
    }

    private static Path configuredFile(String property) {
        String value = System.getProperty(property, "").trim();
        if (value.isBlank()) {
            return null;
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        return Files.isRegularFile(path) ? path : null;
    }

    private static Path require(Path path, String message) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IOException(message);
        }
        return path;
    }

    private static long boundedLongProperty(String name, long fallback, long minimum, long maximum) {
        try {
            long value = Long.parseLong(System.getProperty(name, Long.toString(fallback)));
            return Math.max(minimum, Math.min(maximum, value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    record Capability(boolean available, String ffmpegVersion, String whisperVersion,
            boolean faceModelAvailable, boolean plateModelAvailable, List<String> problems) {
    }
}

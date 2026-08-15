/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import io.github.caipeijia833.docredaction.rules.RuleEngine;
import io.github.caipeijia833.docredaction.rules.SensitiveMatch;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Optional, offline-only Tesseract adapter. No model or language data is downloaded. */
public final class LocalOcrEngine {
    private static final int MAX_TSV_BYTES = 64 * 1024 * 1024;
    private final Capability capability;
    private final long timeoutSeconds;

    public LocalOcrEngine() {
        this.timeoutSeconds = boundedLongProperty("docredaction.ocr.timeoutSeconds", 180L, 10L, 3_600L);
        this.capability = detectCapability();
    }

    public Capability capability() {
        return capability;
    }

    public void redact(BufferedImage image, RuleEngine rules, ProcessReport report,
            Path workDirectory, String unitId) throws IOException {
        List<OcrDetection> detections = detect(image, rules, workDirectory, unitId);
        List<LocalVlmEngine.Detection> vlmDetections = List.of();
        LocalVlmEngine vlm = new LocalVlmEngine();
        if (vlm.capability().available()) {
            try {
                vlmDetections = vlm.detect(image);
            } catch (IOException ex) {
                if (vlm.required()) throw ex;
                report.warning("Qwen3-VL增强检测未完成：" + safeMessage(ex));
            }
        } else if (vlm.required()) {
            throw new IOException(vlm.capability().message());
        }
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.BLACK);
            for (OcrDetection detection : detections) {
                OcrBox box = detection.box();
                int x = Math.max(0, box.left() - 3);
                int y = Math.max(0, box.top() - 3);
                int width = Math.min(image.getWidth() - x, box.width() + 6);
                int height = Math.min(image.getHeight() - y, box.height() + 6);
                if (width > 0 && height > 0) {
                    graphics.fillRect(x, y, width, height);
                    report.addCount(detection.ruleId(), 1);
                }
            }
            for (LocalVlmEngine.Detection detection : vlmDetections) {
                int x = Math.max(0, detection.x() - 4);
                int y = Math.max(0, detection.y() - 4);
                int width = Math.min(image.getWidth() - x, detection.width() + 8);
                int height = Math.min(image.getHeight() - y, detection.height() + 8);
                if (width > 0 && height > 0) {
                    graphics.fillRect(x, y, width, height);
                    report.addCount("VLM_" + detection.type().toUpperCase(Locale.ROOT), 1);
                }
            }
        } finally {
            graphics.dispose();
        }
    }

    /**
     * Detects sensitive OCR regions without modifying the image. This is used by the
     * media timeline so the same coordinates can be reviewed before FFmpeg renders
     * irreversible masks into the output stream.
     */
    public List<OcrDetection> detect(BufferedImage image, RuleEngine rules,
            Path workDirectory, String unitId) throws IOException {
        if (!capability.available()) {
            throw new IOException(capability.message());
        }
        Files.createDirectories(workDirectory);
        String safeUnit = unitId == null ? "page" : unitId.replaceAll("[^a-zA-Z0-9_-]", "_");
        Path temporaryImage = workDirectory.resolve("ocr-" + safeUnit + ".png");
        try {
            if (!ImageIO.write(image, "png", temporaryImage.toFile())) {
                throw new IOException("无法生成OCR临时图像");
            }
            String tsv = runTesseract(temporaryImage);
            return detectMatches(tsv, rules);
        } finally {
            Files.deleteIfExists(temporaryImage);
        }
    }

    static List<OcrBox> detectBoxes(String tsv, RuleEngine rules, ProcessReport report) {
        List<OcrBox> boxes = new ArrayList<>();
        for (OcrDetection detection : detectMatches(tsv, rules)) {
            boxes.add(detection.box());
            report.addCount(detection.ruleId(), 1);
        }
        return boxes;
    }

    static List<OcrDetection> detectMatches(String tsv, RuleEngine rules) {
        Map<String, List<OcrWord>> lines = new LinkedHashMap<>();
        for (String row : tsv.lines().skip(1).toList()) {
            String[] columns = row.split("\\t", 12);
            if (columns.length < 12 || columns[11].isBlank()) {
                continue;
            }
            try {
                String key = columns[1] + ':' + columns[2] + ':' + columns[3] + ':' + columns[4];
                lines.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new OcrWord(
                        Integer.parseInt(columns[6]), Integer.parseInt(columns[7]),
                        Integer.parseInt(columns[8]), Integer.parseInt(columns[9]),
                        Double.parseDouble(columns[10]), columns[11].trim()));
            } catch (NumberFormatException ignored) {
                // Ignore malformed OCR rows rather than trusting invalid coordinates.
            }
        }
        List<OcrDetection> detections = new ArrayList<>();
        for (List<OcrWord> words : lines.values()) {
            StringBuilder text = new StringBuilder();
            List<WordRange> ranges = new ArrayList<>();
            for (OcrWord word : words) {
                int start = text.length();
                text.append(word.text());
                ranges.add(new WordRange(start, text.length(), word));
            }
            for (SensitiveMatch match : rules.detect(text.toString())) {
                OcrBox merged = null;
                double confidence = 0.0d;
                int confidenceCount = 0;
                for (WordRange range : ranges) {
                    if (range.end() > match.start() && range.start() < match.end()) {
                        merged = merged == null ? OcrBox.from(range.word()) : merged.union(range.word());
                        if (range.word().confidence() >= 0.0d) {
                            confidence += range.word().confidence();
                            confidenceCount++;
                        }
                    }
                }
                if (merged != null) {
                    int contextStart = Math.max(0, match.start() - 24);
                    int contextEnd = Math.min(text.length(), match.end() + 24);
                    detections.add(new OcrDetection(match.ruleId(), match.label(),
                            text.substring(match.start(), match.end()),
                            text.substring(contextStart, contextEnd), merged,
                            confidenceCount == 0 ? 0.0d : confidence / confidenceCount / 100.0d));
                }
            }
        }
        return List.copyOf(detections);
    }

    private String runTesseract(Path image) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(capability.executable().toString());
        command.addAll(List.of(image.toString(), "stdout", "-l", capability.languages(),
                "--oem", "1", "--psm", "6"));
        if (capability.dataPath() != null) {
            command.addAll(List.of("--tessdata-dir", capability.dataPath().toString()));
        }
        command.add("tsv");
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(false)
                .start();
        FutureTask<byte[]> stdout = new FutureTask<>(() -> readBounded(process.getInputStream(), MAX_TSV_BYTES));
        FutureTask<byte[]> stderr = new FutureTask<>(() -> readBounded(process.getErrorStream(), 64 * 1024));
        Thread.startVirtualThread(stdout);
        Thread.startVirtualThread(stderr);
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                terminate(process);
                throw new IOException("OCR单页处理超时（" + timeoutSeconds + "秒）");
            }
            byte[] bytes = stdout.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw new IOException("本地OCR进程失败（代码" + process.exitValue() + "）");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (InterruptedException ex) {
            terminate(process);
            Thread.currentThread().interrupt();
            throw new IOException("OCR处理已取消", ex);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ex) {
            terminate(process);
            throw new IOException("无法读取OCR结果", ex);
        }
    }

    private static Capability detectCapability() {
        Path executable = findExecutable();
        if (executable == null) {
            return new Capability(false, null, "", "", null,
                    "未找到本地Tesseract OCR；扫描件和图片按失败关闭策略停止处理");
        }
        Process process = null;
        try {
            Path dataPath = findDataPath(executable);
            List<String> languageCommand = new ArrayList<>(List.of(executable.toString(), "--list-langs"));
            if (dataPath != null) {
                languageCommand.addAll(List.of("--tessdata-dir", dataPath.toString()));
            }
            process = new ProcessBuilder(languageCommand).redirectErrorStream(true).start();
            Process started = process;
            FutureTask<byte[]> outputTask = new FutureTask<>(
                    () -> readBounded(started.getInputStream(), 1024 * 1024));
            Thread.startVirtualThread(outputTask);
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                terminate(process);
                return new Capability(false, executable, "", "", dataPath, "本地Tesseract OCR无法正常启动");
            }
            byte[] output = outputTask.get(2, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                return new Capability(false, executable, "", "", dataPath, "本地Tesseract OCR无法正常启动");
            }
            String languages = new String(output, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            if (!languages.lines().anyMatch(line -> line.trim().equals("chi_sim"))) {
                return new Capability(false, executable, "", detectVersion(executable), dataPath,
                        "本地Tesseract缺少chi_sim中文语言包");
            }
            boolean english = languages.lines().anyMatch(line -> line.trim().equals("eng"));
            return new Capability(true, executable, english ? "chi_sim+eng" : "chi_sim",
                    detectVersion(executable), dataPath, "本地OCR可用");
        } catch (IOException | InterruptedException | java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException ex) {
            if (process != null) {
                terminate(process);
            }
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new Capability(false, executable, "", "", findDataPath(executable),
                    "本地Tesseract OCR能力检查失败");
        }
    }

    private static Path findExecutable() {
        String configured = System.getProperty("docredaction.ocr.executable", "").trim();
        if (!configured.isBlank()) {
            Path path = Path.of(configured).toAbsolutePath().normalize();
            return Files.isRegularFile(path) ? path : null;
        }
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "tesseract.exe" : "tesseract";
        String pathValue = System.getenv("PATH");
        if (pathValue != null) {
            for (String part : pathValue.split(java.io.File.pathSeparator)) {
                if (!part.isBlank()) {
                    Path candidate = Path.of(part).resolve(name).toAbsolutePath().normalize();
                    if (Files.isRegularFile(candidate)) {
                        return candidate;
                    }
                }
            }
        }
        List<Path> candidates = List.of(
                Path.of("ocr", "bin", name),
                Path.of("C:\\Program Files\\Tesseract-OCR\\tesseract.exe"),
                Path.of("/opt/homebrew/bin/tesseract"), Path.of("/usr/local/bin/tesseract"),
                Path.of("/usr/bin/tesseract"));
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
    }

    private static Path findDataPath(Path executable) {
        String configured = System.getProperty("docredaction.ocr.dataPath", "").trim();
        if (!configured.isBlank()) {
            Path path = Path.of(configured).toAbsolutePath().normalize();
            return Files.isDirectory(path) ? path : null;
        }
        if (executable == null || executable.getParent() == null) {
            return null;
        }
        List<Path> candidates = new ArrayList<>();
        Path bin = executable.getParent();
        if (bin.getParent() != null) {
            candidates.add(bin.getParent().resolve("share").resolve("tessdata"));
        }
        candidates.add(bin.resolve("tessdata"));
        return candidates.stream().map(path -> path.toAbsolutePath().normalize())
                .filter(Files::isDirectory).findFirst().orElse(null);
    }

    private static String detectVersion(Path executable) {
        Process process = null;
        try {
            process = new ProcessBuilder(executable.toString(), "--version").redirectErrorStream(true).start();
            Process started = process;
            FutureTask<byte[]> outputTask = new FutureTask<>(() -> readBounded(started.getInputStream(), 64 * 1024));
            Thread.startVirtualThread(outputTask);
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                terminate(process);
                return "";
            }
            String firstLine = new String(outputTask.get(2, TimeUnit.SECONDS), StandardCharsets.UTF_8)
                    .lines().findFirst().orElse("").trim();
            return firstLine.replaceFirst("(?i)^tesseract\\s+", "");
        } catch (Exception ex) {
            if (process != null) {
                terminate(process);
            }
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "";
        }
    }

    private static byte[] readBounded(InputStream input, int maximum) throws IOException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maximum) {
                    throw new IOException("本地OCR输出超过安全上限");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void terminate(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static long boundedLongProperty(String name, long fallback, long minimum, long maximum) {
        try {
            long value = Long.parseLong(System.getProperty(name, Long.toString(fallback)));
            return Math.max(minimum, Math.min(maximum, value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String safeMessage(IOException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "本地模型返回异常";
        message = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return message.length() > 180 ? message.substring(0, 180) : message;
    }

    public record Capability(boolean available, Path executable, String languages, String version,
            Path dataPath, String message) {
    }

    record OcrBox(int left, int top, int width, int height) {
        static OcrBox from(OcrWord word) {
            return new OcrBox(word.left(), word.top(), word.width(), word.height());
        }

        OcrBox union(OcrWord word) {
            int right = Math.max(left + width, word.left() + word.width());
            int bottom = Math.max(top + height, word.top() + word.height());
            int newLeft = Math.min(left, word.left());
            int newTop = Math.min(top, word.top());
            return new OcrBox(newLeft, newTop, right - newLeft, bottom - newTop);
        }
    }

    public record OcrDetection(String ruleId, String label, String value,
            String context, OcrBox box, double confidence) {
    }

    private record OcrWord(int left, int top, int width, int height,
            double confidence, String text) {
    }

    private record WordRange(int start, int end, OcrWord word) {
    }
}

/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import io.github.caipeijia833.docredaction.rules.RuleEngine;
import io.github.caipeijia833.docredaction.rules.SensitiveMatch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Performs local ASR, sampled-frame OCR and CPU visual analysis without network access. */
final class MediaAnalyzer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_FINDINGS = 20_000;
    private static final long MAX_SUBTITLE_BYTES = 64L * 1024L * 1024L;
    private static final Pattern SRT_BLOCK = Pattern.compile(
            "(?ms)^\\s*\\d+\\s*\\n(\\d{2}:\\d{2}:\\d{2},\\d{3})\\s+-->\\s+"
                    + "(\\d{2}:\\d{2}:\\d{2},\\d{3})[^\\n]*\\n(.*?)(?=\\n\\s*\\n|\\z)");

    private final MediaToolchain tools;

    MediaAnalyzer() {
        this(new MediaToolchain());
    }

    MediaAnalyzer(MediaToolchain tools) {
        this.tools = tools;
    }

    MediaAnalysis analyze(Path input, RuleEngine rules, Path workDirectory) throws Exception {
        MediaProbe.Result probe = MediaProbe.inspect(input, tools);
        MediaToolchain.Capability capability = tools.capability();
        if (!capability.available()) {
            throw new IOException("本地音视频组件未就绪：" + String.join("；", capability.problems()));
        }
        Path work = workDirectory.toAbsolutePath().normalize();
        Files.createDirectories(work);
        List<MediaFinding> findings = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (MediaProbe.Stream audio : probe.audioStreams()) {
            findings.addAll(analyzeAudio(input, audio, rules, work));
            enforceFindingLimit(findings);
        }
        if (!probe.primaryVideoStreams().isEmpty()) {
            VideoAnalysis visual = analyzeVideo(input, probe.primaryVideoStreams().getFirst(),
                    probe.durationMillis(), rules, work);
            findings.addAll(visual.findings());
            warnings.addAll(visual.warnings());
            warnings.add(String.format(Locale.ROOT,
                    "视频画面按%.3f fps抽样（约每%.2f秒一帧，共%d帧）；短于抽样间隔的闪现内容不能保证检出。",
                    visual.sampleFps(), 1.0d / visual.sampleFps(), visual.frameCount()));
            enforceFindingLimit(findings);
        }
        if (!probe.subtitleStreams().isEmpty()) {
            int textTracks = 0;
            int removedTracks = 0;
            for (MediaProbe.Stream subtitle : probe.subtitleStreams()) {
                if (MediaProbe.TEXT_SUBTITLE_CODECS.contains(subtitle.codec())) {
                    findings.addAll(analyzeSubtitle(input, subtitle, rules, work));
                    textTracks++;
                } else {
                    removedTracks++;
                }
                enforceFindingLimit(findings);
            }
            if (textTracks > 0) {
                warnings.add("已检查并将以SRT或MOV_TEXT重新封装" + textTracks
                        + "条文本字幕；原字幕字体、ASS特效和位置样式不保留。");
            }
            if (removedTracks > 0) {
                warnings.add("输出将移除" + removedTracks
                        + "条位图或未知字幕轨，避免未复核图像字幕泄露敏感信息。");
            }
        }
        if (!probe.removedStreams().isEmpty()) {
            warnings.add("输出将移除" + probe.removedStreams().size()
                    + "条数据、附件或未知轨道，不复制未检查内容。");
        }
        warnings.add("音频使用本地whisper.cpp转写；视觉按抽帧策略检测，极短闪现、严重遮挡或低清内容仍需人工复核。");
        List<MediaFinding> consolidated = consolidate(findings).stream()
                .filter(finding -> !isWhitelisted(finding, rules.whitelist())).toList();
        return new MediaAnalysis(probe, consolidated, warnings);
    }

    private List<MediaFinding> analyzeAudio(Path input, MediaProbe.Stream stream,
            RuleEngine rules, Path work) throws IOException {
        Path wav = work.resolve("audio-stream-" + stream.index() + ".wav");
        Path jsonBase = work.resolve("asr-stream-" + stream.index());
        Path jsonFile = Path.of(jsonBase + ".json");
        try {
            tools.run(List.of(tools.ffmpeg().toString(), "-nostdin", "-hide_banner", "-loglevel", "error",
                    "-y", "-i", input.toString(), "-map", "0:" + stream.index(),
                    "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", "-f", "wav",
                    wav.toString()));
            int threads = boundedIntProperty("docredaction.media.asrThreads",
                    Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)), 1, 16);
            tools.run(List.of(tools.whisper().toString(), "-m", tools.whisperModel().toString(),
                    "-f", wav.toString(), "-l", "auto", "-t", Integer.toString(threads),
                    "-ojf", "-of", jsonBase.toString(), "-np"));
            if (!Files.isRegularFile(jsonFile)) {
                throw new IOException("本地ASR未生成结构化转写结果");
            }
            return parseTranscription(jsonFile, stream.index(), rules);
        } finally {
            Files.deleteIfExists(wav);
            Files.deleteIfExists(jsonFile);
        }
    }

    private List<MediaFinding> analyzeSubtitle(Path input, MediaProbe.Stream stream,
            RuleEngine rules, Path work) throws IOException {
        Path subtitle = work.resolve("subtitle-stream-" + stream.index() + ".srt");
        try {
            tools.run(List.of(tools.ffmpeg().toString(), "-nostdin", "-hide_banner", "-loglevel", "error",
                    "-y", "-i", input.toString(), "-map", "0:" + stream.index(),
                    "-c:s", "srt", "-f", "srt", subtitle.toString()));
            if (Files.size(subtitle) > MAX_SUBTITLE_BYTES) {
                throw new IOException("文本字幕轨超过64MiB安全上限");
            }
            String content = Files.readString(subtitle).replace("\r\n", "\n").replace('\r', '\n');
            Matcher matcher = SRT_BLOCK.matcher(content);
            List<MediaFinding> findings = new ArrayList<>();
            while (matcher.find()) {
                long start = parseSrtTime(matcher.group(1));
                long end = parseSrtTime(matcher.group(2));
                String text = matcher.group(3).replaceAll("<[^>]{1,200}>", " ")
                        .replaceAll("[\\r\\n\\t]+", " ").trim();
                for (SensitiveMatch match : rules.detect(text)) {
                    findings.add(new MediaFinding(MediaFinding.Kind.SUBTITLE, stream.index(),
                            match.ruleId(), match.label(), text.substring(match.start(), match.end()), text,
                            start, Math.max(start + 100L, end), 0, 0, 0, 0, 1.0d));
                }
            }
            return findings;
        } finally {
            Files.deleteIfExists(subtitle);
        }
    }

    private static long parseSrtTime(String value) throws IOException {
        try {
            String[] parts = value.split("[:,]");
            return Long.parseLong(parts[0]) * 3_600_000L
                    + Long.parseLong(parts[1]) * 60_000L
                    + Long.parseLong(parts[2]) * 1_000L
                    + Long.parseLong(parts[3]);
        } catch (RuntimeException ex) {
            throw new IOException("文本字幕时间戳无效", ex);
        }
    }

    private static List<MediaFinding> parseTranscription(Path jsonFile, int streamIndex,
            RuleEngine rules) throws IOException {
        JsonNode root = JSON.readTree(jsonFile.toFile());
        JsonNode transcription = root.path("transcription");
        if (!transcription.isArray()) {
            throw new IOException("本地ASR结果缺少transcription数组");
        }
        List<MediaFinding> findings = new ArrayList<>();
        Set<String> duplicates = new HashSet<>();
        for (JsonNode segment : transcription) {
            String text = segment.path("text").asText("").trim();
            if (text.isBlank()) {
                continue;
            }
            long start = Math.max(0L, segment.path("offsets").path("from").asLong(0L) - 250L);
            long end = Math.max(start + 100L, segment.path("offsets").path("to").asLong(start + 100L) + 250L);
            double confidence = tokenConfidence(segment.path("tokens"));
            addAudioMatches(findings, duplicates, rules, text, streamIndex, start, end, confidence);
            String normalized = normalizeSpokenDigits(text);
            if (!normalized.equals(text)) {
                addAudioMatches(findings, duplicates, rules, normalized, streamIndex, start, end, confidence);
            }
        }
        return findings;
    }

    private static void addAudioMatches(List<MediaFinding> findings, Set<String> duplicates,
            RuleEngine rules, String text, int streamIndex, long start, long end, double confidence) {
        for (SensitiveMatch match : rules.detect(text)) {
            String value = text.substring(match.start(), match.end());
            String key = match.ruleId() + '|' + start + '|' + end + '|' + value.toLowerCase(Locale.ROOT);
            if (duplicates.add(key)) {
                findings.add(new MediaFinding(MediaFinding.Kind.AUDIO, streamIndex,
                        match.ruleId(), match.label(), value, text, start, end,
                        0, 0, 0, 0, confidence));
            }
        }
    }

    private VideoAnalysis analyzeVideo(Path input, MediaProbe.Stream stream, long containerDurationMillis,
            RuleEngine rules, Path work) throws Exception {
        Path frames = work.resolve("sampled-frames");
        Files.createDirectories(frames);
        double configuredFps = boundedDoubleProperty("docredaction.media.analysisFps", 1.0d, 0.1d, 5.0d);
        int maxFrames = boundedIntProperty("docredaction.media.maxSampleFrames", 900, 10, 20_000);
        long durationMillis = stream.durationMillis() > 0 ? stream.durationMillis() : containerDurationMillis;
        double durationSeconds = Math.max(1.0d, durationMillis / 1_000.0d);
        double sampleFps = Math.min(configuredFps, maxFrames / durationSeconds);
        sampleFps = Math.max(0.1d, sampleFps);
        String fpsText = String.format(Locale.ROOT, "%.6f", sampleFps);
        Path pattern = frames.resolve("frame-%08d.jpg");
        try {
            tools.run(List.of(tools.ffmpeg().toString(), "-nostdin", "-hide_banner", "-loglevel", "error",
                    "-y", "-i", input.toString(), "-map", "0:" + stream.index(),
                    "-an", "-sn", "-dn", "-vf", "fps=" + fpsText
                            + ",scale=1280:-2:force_original_aspect_ratio=decrease",
                    "-frames:v", Integer.toString(maxFrames), "-q:v", "5", pattern.toString()));
            LocalOcrEngine ocr = new LocalOcrEngine();
            if (!ocr.capability().available()) {
                throw new IOException("视频画面文字检测不可用：" + ocr.capability().message());
            }
            LocalVlmEngine vlm = new LocalVlmEngine();
            if (vlm.required() && !vlm.capability().available()) {
                throw new IOException(vlm.capability().message());
            }
            List<Path> images;
            try (Stream<Path> listed = Files.list(frames)) {
                images = listed.filter(Files::isRegularFile).sorted().toList();
            }
            List<MediaFinding> result = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            boolean vlmFailed = false;
            try (OpenCvMediaDetector detector = new OpenCvMediaDetector(tools.faceModel(), tools.plateModel())) {
                for (int index = 0; index < images.size(); index++) {
                    BufferedImage image = ImageIO.read(images.get(index).toFile());
                    if (image == null) {
                        throw new IOException("无法读取FFmpeg抽取的视频帧");
                    }
                    long center = Math.round(index * 1_000.0d / sampleFps);
                    long radius = Math.max(300L, Math.round(600.0d / sampleFps));
                    long start = Math.max(0L, center - radius);
                    long end = Math.min(Math.max(start + 100L, durationMillis), center + radius);
                    double scaleX = stream.width() / (double) image.getWidth();
                    double scaleY = stream.height() / (double) image.getHeight();
                    for (OpenCvMediaDetector.VisualBox box : detector.detect(image)) {
                        result.add(visualFinding(box.kind(), stream.index(), visualRuleId(box.kind()),
                                visualLabel(box.kind()), box.value(), box.value(), start, end,
                                scaled(box.x(), scaleX), scaled(box.y(), scaleY),
                                scaled(box.width(), scaleX), scaled(box.height(), scaleY), box.confidence()));
                    }
                    for (LocalOcrEngine.OcrDetection detection : ocr.detect(image, rules,
                            work.resolve("ocr"), "video-" + index)) {
                        LocalOcrEngine.OcrBox box = detection.box();
                        result.add(visualFinding(MediaFinding.Kind.OCR_TEXT, stream.index(),
                                detection.ruleId(), detection.label(), detection.value(), detection.context(),
                                start, end, scaled(box.left(), scaleX), scaled(box.top(), scaleY),
                                scaled(box.width(), scaleX), scaled(box.height(), scaleY), detection.confidence()));
                    }
                    try {
                        for (LocalVlmEngine.Detection detection : vlm.detect(image)) {
                            result.add(visualFinding(MediaFinding.Kind.OCR_TEXT, stream.index(),
                                    "VLM_" + detection.type().toUpperCase(Locale.ROOT), detection.label(), "",
                                    detection.label(), start, end, scaled(detection.x(), scaleX),
                                    scaled(detection.y(), scaleY), scaled(detection.width(), scaleX),
                                    scaled(detection.height(), scaleY), detection.confidence()));
                        }
                    } catch (IOException ex) {
                        if (vlm.required()) throw ex;
                        if (!vlmFailed) {
                            warnings.add("本地Qwen3-VL视频增强检测失败，已保留规则、OCR和OpenCV检测结果："
                                    + safeMessage(ex));
                            vlmFailed = true;
                        }
                    }
                    Files.deleteIfExists(images.get(index));
                    enforceFindingLimit(result);
                }
            }
            TemporalVisualTracker.Result tracked = TemporalVisualTracker.bridge(result, sampleFps);
            if (tracked.bridgeCount() > 0) {
                warnings.add("已根据相邻采样帧生成" + tracked.bridgeCount()
                        + "个保守跨帧轨迹遮挡区，降低快速移动对象在帧间暴露的风险。");
            }
            return new VideoAnalysis(tracked.findings(), sampleFps, images.size(), List.copyOf(warnings));
        } finally {
            deleteTree(frames);
        }
    }

    private record VideoAnalysis(List<MediaFinding> findings, double sampleFps, int frameCount,
            List<String> warnings) {
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        return message.replaceAll("[\\r\\n\\t]+", " ").trim();
    }

    private static MediaFinding visualFinding(MediaFinding.Kind kind, int streamIndex,
            String ruleId, String label, String value, String context, long start, long end,
            int x, int y, int width, int height, double confidence) {
        return new MediaFinding(kind, streamIndex, ruleId, label, value, context,
                start, Math.max(start + 100L, end), Math.max(0, x), Math.max(0, y),
                Math.max(2, width), Math.max(2, height), confidence);
    }

    private static int scaled(int value, double scale) {
        return Math.max(0, (int) Math.round(value * scale));
    }

    private static String visualRuleId(MediaFinding.Kind kind) {
        return switch (kind) {
            case FACE -> "VISUAL_FACE";
            case LICENSE_PLATE -> "VISUAL_LICENSE_PLATE";
            case QR_CODE -> "VISUAL_QR_CODE";
            default -> throw new IllegalArgumentException("非视觉对象类型");
        };
    }

    private static String visualLabel(MediaFinding.Kind kind) {
        return switch (kind) {
            case FACE -> "人脸";
            case LICENSE_PLATE -> "车辆号牌图像";
            case QR_CODE -> "二维码";
            default -> throw new IllegalArgumentException("非视觉对象类型");
        };
    }

    private static List<MediaFinding> consolidate(List<MediaFinding> source) {
        List<MediaFinding> sorted = new ArrayList<>(source);
        sorted.sort(Comparator.comparingInt(MediaFinding::streamIndex)
                .thenComparing(MediaFinding::kind)
                .thenComparing(MediaFinding::ruleId)
                .thenComparingLong(MediaFinding::startMillis));
        List<MediaFinding> result = new ArrayList<>();
        for (MediaFinding next : sorted) {
            if (!result.isEmpty()) {
                MediaFinding previous = result.getLast();
                boolean sameRule = previous.ruleId().equals(next.ruleId());
                // Keep visual observations and temporal bridges separate: merging
                // a long moving track into one static rectangle can over-mask most
                // of the frame and defeats per-segment timing.
                boolean merge = sameRule && !previous.visual() && !next.visual()
                        && previous.overlapsInTime(next, 800L);
                if (merge) {
                    result.set(result.size() - 1, previous.merge(next));
                    continue;
                }
            }
            result.add(next);
        }
        return List.copyOf(result);
    }

    private static double tokenConfidence(JsonNode tokens) {
        if (!tokens.isArray() || tokens.isEmpty()) {
            return 0.0d;
        }
        double total = 0.0d;
        int count = 0;
        for (JsonNode token : tokens) {
            double probability = token.path("p").asDouble(-1.0d);
            if (probability >= 0.0d && probability <= 1.0d) {
                total += probability;
                count++;
            }
        }
        return count == 0 ? 0.0d : total / count;
    }

    static String normalizeSpokenDigits(String text) {
        String normalized = text;
        String[][] english = {
                {"zero", "0"}, {"oh", "0"}, {"one", "1"}, {"two", "2"},
                {"three", "3"}, {"four", "4"}, {"five", "5"}, {"six", "6"},
                {"seven", "7"}, {"eight", "8"}, {"nine", "9"}
        };
        for (String[] entry : english) {
            normalized = normalized.replaceAll("(?i)\\b" + entry[0] + "\\b", entry[1]);
        }
        normalized = normalized.replace('零', '0').replace('〇', '0')
                .replace('一', '1').replace('二', '2').replace('两', '2')
                .replace('三', '3').replace('四', '4').replace('五', '5')
                .replace('六', '6').replace('七', '7').replace('八', '8').replace('九', '9');
        String previous;
        do {
            previous = normalized;
            normalized = normalized.replaceAll("(?<=\\d)[\\s,，.。·-]+(?=\\d)", "");
        } while (!normalized.equals(previous));
        return normalized;
    }

    private static void enforceFindingLimit(List<MediaFinding> findings) throws IOException {
        if (findings.size() > MAX_FINDINGS) {
            throw new IOException("音视频敏感命中超过" + MAX_FINDINGS + "项安全上限，请拆分文件后处理");
        }
    }

    private static boolean isWhitelisted(MediaFinding finding, List<String> whitelist) {
        for (String value : whitelist) {
            if (finding.ignoreKey().equals(value)
                    || (!finding.value().isBlank() && finding.value().equalsIgnoreCase(value))) {
                return true;
            }
        }
        return false;
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static int boundedIntProperty(String name, int fallback, int minimum, int maximum) {
        try {
            int value = Integer.parseInt(System.getProperty(name, Integer.toString(fallback)));
            return Math.max(minimum, Math.min(maximum, value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static double boundedDoubleProperty(String name, double fallback,
            double minimum, double maximum) {
        try {
            double value = Double.parseDouble(System.getProperty(name, Double.toString(fallback)));
            return Double.isFinite(value) ? Math.max(minimum, Math.min(maximum, value)) : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}

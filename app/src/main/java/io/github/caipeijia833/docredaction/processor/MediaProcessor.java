/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import io.github.caipeijia833.docredaction.rules.RuleEngine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Renders irreversible audio muting and visual masks into a newly encoded local media file. */
public final class MediaProcessor implements DocumentProcessor {
    private static final Set<String> EXTENSIONS = MediaProbe.EXTENSIONS;

    @Override
    public Set<String> extensions() {
        return EXTENSIONS;
    }

    @Override
    public ProcessReport process(Path input, Path output, RuleEngine ruleEngine) throws Exception {
        MediaToolchain tools = new MediaToolchain();
        Path work = output.getParent().resolve("media-work-"
                + Integer.toUnsignedString(input.toAbsolutePath().normalize().toString().hashCode(), 36));
        Files.createDirectories(work);
        try {
            MediaAnalysis analysis = new MediaAnalyzer(tools).analyze(input, ruleEngine, work.resolve("analysis"));
            List<MediaFinding> active = analysis.findings().stream()
                    .filter(finding -> !ignored(finding, ruleEngine.whitelist())).toList();
            render(input, output, analysis.probe(), active, ruleEngine, tools, work);
            MediaProbe.inspect(output, tools);
            ProcessReport report = new ProcessReport();
            for (MediaFinding finding : active) {
                report.addCount(finding.ruleId(), 1);
            }
            report.addUnits(Math.max(1, (int) Math.min(Integer.MAX_VALUE,
                    Math.ceil(analysis.probe().durationMillis() / 1_000.0d))));
            analysis.warnings().forEach(report::warning);
            long visualCount = active.stream().filter(MediaFinding::visual).count();
            long subtitleCount = active.stream().filter(finding -> finding.kind() == MediaFinding.Kind.SUBTITLE).count();
            long audioCount = active.stream().filter(finding -> finding.kind() == MediaFinding.Kind.AUDIO).count();
            report.warning("已重新编码音视频并清除容器元数据、章节、位图字幕及未检查轨道；音频静音区间"
                    + audioCount + "个，画面遮挡区间" + visualCount + "个，文本字幕替换命中"
                    + subtitleCount + "个。");
            return report;
        } finally {
            deleteTree(work);
        }
    }

    private static boolean ignored(MediaFinding finding, List<String> whitelist) {
        for (String value : whitelist) {
            if (finding.ignoreKey().equals(value)
                    || (!finding.value().isBlank() && finding.value().equalsIgnoreCase(value))) {
                return true;
            }
        }
        return false;
    }

    private static void render(Path input, Path output, MediaProbe.Result probe,
            List<MediaFinding> findings, RuleEngine rules, MediaToolchain tools, Path work) throws IOException {
        String extension = extension(input);
        validateContainer(extension, probe);
        Map<Integer, List<MediaFinding>> byStream = new HashMap<>();
        for (MediaFinding finding : findings) {
            byStream.computeIfAbsent(finding.streamIndex(), ignored -> new ArrayList<>()).add(finding);
        }

        List<String> filterLines = new ArrayList<>();
        List<String> videoLabels = new ArrayList<>();
        int labelSequence = 0;
        for (MediaProbe.Stream video : probe.primaryVideoStreams()) {
            String current = "0:" + video.index();
            List<MediaFinding> visual = byStream.getOrDefault(video.index(), List.of()).stream()
                    .filter(MediaFinding::visual).toList();
            if (visual.isEmpty()) {
                String label = "v" + labelSequence++;
                filterLines.add("[" + current + "]null[" + label + "]");
                current = label;
            } else {
                for (MediaFinding finding : visual) {
                    String label = "v" + labelSequence++;
                    filterLines.add("[" + current + "]drawbox="
                            + "x=" + clip(finding.x(), 0, video.width() - 1)
                            + ":y=" + clip(finding.y(), 0, video.height() - 1)
                            + ":w=" + clip(finding.width(), 2, video.width())
                            + ":h=" + clip(finding.height(), 2, video.height())
                            + ":color=black:t=fill:enable='between(t,"
                            + seconds(finding.startMillis()) + ',' + seconds(finding.endMillis())
                            + ")'[" + label + "]");
                    current = label;
                }
            }
            videoLabels.add(current);
        }

        List<String> audioLabels = new ArrayList<>();
        int audioSequence = 0;
        for (MediaProbe.Stream audio : probe.audioStreams()) {
            String current = "0:" + audio.index();
            List<MediaFinding> audioFindings = byStream.getOrDefault(audio.index(), List.of()).stream()
                    .filter(finding -> !finding.visual()).toList();
            if (audioFindings.isEmpty()) {
                String label = "a" + audioSequence++;
                filterLines.add("[" + current + "]anull[" + label + "]");
                current = label;
            } else {
                for (MediaFinding finding : audioFindings) {
                    String label = "a" + audioSequence++;
                    filterLines.add("[" + current + "]volume=0:enable='between(t,"
                            + seconds(finding.startMillis()) + ',' + seconds(finding.endMillis())
                            + ")'[" + label + "]");
                    current = label;
                }
            }
            audioLabels.add(current);
        }

        Path filterScript = work.resolve("redaction-filter.txt");
        Files.writeString(filterScript, String.join(";\n", filterLines), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        List<Path> subtitles = prepareRedactedSubtitles(input, probe, rules, tools, work);
        List<String> command = new ArrayList<>(List.of(tools.ffmpeg().toString(),
                "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-i", input.toString()));
        for (Path subtitle : subtitles) {
            command.addAll(List.of("-f", "srt", "-i", subtitle.toString()));
        }
        command.addAll(List.of("-filter_complex_script", filterScript.toString()));
        for (String label : videoLabels) {
            command.addAll(List.of("-map", "[" + label + "]"));
        }
        for (String label : audioLabels) {
            command.addAll(List.of("-map", "[" + label + "]"));
        }
        for (int index = 0; index < subtitles.size(); index++) {
            command.addAll(List.of("-map", (index + 1) + ":0"));
        }
        command.addAll(List.of("-map_metadata", "-1", "-map_chapters", "-1"));
        if (!videoLabels.isEmpty()) {
            command.addAll(List.of("-c:v", selectedVideoEncoder(), "-q:v", "4", "-pix_fmt", "yuv420p"));
        }
        if (!audioLabels.isEmpty()) {
            command.addAll(audioEncodingArguments(extension));
        }
        if (!subtitles.isEmpty()) {
            command.addAll(List.of("-c:s", Set.of("mp4", "mov").contains(extension) ? "mov_text" : "srt"));
        }
        if ("mp4".equals(extension) || "mov".equals(extension) || "m4a".equals(extension)) {
            command.addAll(List.of("-movflags", "+faststart"));
        }
        command.addAll(List.of("-f", muxer(extension), output.toString()));
        tools.run(command);
        if (!Files.isRegularFile(output) || Files.size(output) <= 0L) {
            throw new IOException("FFmpeg未生成有效的脱敏输出文件");
        }
    }

    private static List<Path> prepareRedactedSubtitles(Path input, MediaProbe.Result probe,
            RuleEngine rules, MediaToolchain tools, Path work) throws IOException {
        List<Path> results = new ArrayList<>();
        for (MediaProbe.Stream stream : probe.subtitleStreams()) {
            if (!MediaProbe.TEXT_SUBTITLE_CODECS.contains(stream.codec())) {
                continue;
            }
            Path extracted = work.resolve("subtitle-" + stream.index() + "-raw.srt");
            Path redacted = work.resolve("subtitle-" + stream.index() + "-redacted.srt");
            tools.run(List.of(tools.ffmpeg().toString(), "-nostdin", "-hide_banner", "-loglevel", "error",
                    "-y", "-i", input.toString(), "-map", "0:" + stream.index(),
                    "-c:s", "srt", "-f", "srt", extracted.toString()));
            long size = Files.size(extracted);
            if (size > 64L * 1024L * 1024L) {
                throw new IOException("文本字幕轨超过64MiB安全上限");
            }
            String content = Files.readString(extracted, StandardCharsets.UTF_8);
            String replacement = rules.redact(content).redactedText();
            Files.writeString(redacted, replacement, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.deleteIfExists(extracted);
            results.add(redacted);
        }
        return List.copyOf(results);
    }

    private static void validateContainer(String extension, MediaProbe.Result probe) throws IOException {
        if (MediaProbe.AUDIO_EXTENSIONS.contains(extension)
                && probe.primaryVideoStreams().size() > 0) {
            throw new IOException("音频扩展名文件中包含视频轨，拒绝改变文件语义");
        }
        if (probe.audioStreams().size() > 1 && Set.of("mp3", "wav", "flac").contains(extension)) {
            throw new IOException(extension + "容器包含多条音轨，当前版本无法在保留格式的同时安全输出；请先转为m4a或mkv");
        }
        if (!probe.subtitleStreams().isEmpty()
                && !Set.of("mp4", "mov", "mkv").contains(extension)) {
            throw new IOException(extension + "容器含字幕轨但目标格式不能安全保留字幕，请先转为mp4或mkv");
        }
    }

    private static String selectedVideoEncoder() {
        String configured = System.getProperty("docredaction.media.videoEncoder", "").trim();
        if (!configured.isBlank() && configured.matches("[a-zA-Z0-9_]+")) {
            return configured;
        }
        return "mpeg4";
    }

    private static List<String> audioEncodingArguments(String extension) {
        return switch (extension) {
            case "mp3" -> List.of("-c:a", "libmp3lame", "-q:a", "3");
            case "wav" -> List.of("-c:a", "pcm_s16le");
            case "flac" -> List.of("-c:a", "flac", "-compression_level", "5");
            default -> List.of("-c:a", "aac", "-b:a", "160k");
        };
    }

    private static String muxer(String extension) throws IOException {
        return switch (extension) {
            case "mp4" -> "mp4";
            case "mov" -> "mov";
            case "mkv" -> "matroska";
            case "mp3" -> "mp3";
            case "wav" -> "wav";
            case "m4a" -> "ipod";
            case "flac" -> "flac";
            default -> throw new IOException("不支持的音视频输出容器：" + extension);
        };
    }

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.3f", millis / 1_000.0d);
    }

    private static int clip(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}

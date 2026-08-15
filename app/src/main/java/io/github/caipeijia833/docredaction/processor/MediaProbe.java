/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Strict ffprobe model. Unknown streams are removed from output rather than copied through. */
public final class MediaProbe {
    static final Set<String> AUDIO_EXTENSIONS = Set.of("mp3", "wav", "m4a", "flac");
    static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "mov", "mkv");
    static final Set<String> EXTENSIONS = Set.of("mp3", "wav", "m4a", "flac", "mp4", "mov", "mkv");
    static final Set<String> TEXT_SUBTITLE_CODECS = Set.of(
            "subrip", "srt", "ass", "ssa", "webvtt", "mov_text", "text");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long DEFAULT_VIDEO_MAX_MILLIS = 30L * 60L * 1_000L;
    private static final long DEFAULT_AUDIO_MAX_MILLIS = 4L * 60L * 60L * 1_000L;

    private MediaProbe() {
    }

    public static boolean isSupportedFileName(String name) {
        if (name == null) {
            return false;
        }
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return EXTENSIONS.contains(extension);
    }

    static Result inspect(Path input, MediaToolchain tools) throws IOException {
        String json = tools.runText(List.of(
                tools.ffprobe().toString(), "-v", "error",
                "-show_entries",
                "format=duration,format_name:stream=index,codec_type,codec_name,width,height,duration,avg_frame_rate,sample_rate,channels:stream_disposition=attached_pic",
                "-of", "json", input.toString()), 60L);
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (IOException ex) {
            throw new IOException("ffprobe返回了无效JSON", ex);
        }
        JsonNode format = root.path("format");
        long formatDuration = secondsToMillis(format.path("duration").asText(""));
        String formatName = format.path("format_name").asText("");
        List<Stream> streams = new ArrayList<>();
        JsonNode streamArray = root.path("streams");
        if (!streamArray.isArray() || streamArray.size() > 64) {
            throw new IOException("音视频轨道数量无效或超过64条安全上限");
        }
        for (JsonNode node : streamArray) {
            int index = node.path("index").asInt(-1);
            if (index < 0 || index > 10_000) {
                throw new IOException("ffprobe返回了无效轨道编号");
            }
            String type = node.path("codec_type").asText("").toLowerCase(Locale.ROOT);
            String codec = node.path("codec_name").asText("").toLowerCase(Locale.ROOT);
            long duration = secondsToMillis(node.path("duration").asText(""));
            int width = node.path("width").asInt(0);
            int height = node.path("height").asInt(0);
            double fps = parseRate(node.path("avg_frame_rate").asText(""));
            int sampleRate = parseInteger(node.path("sample_rate").asText(""));
            int channels = node.path("channels").asInt(0);
            boolean attachedPicture = node.path("disposition").path("attached_pic").asInt(0) == 1;
            streams.add(new Stream(index, type, codec, duration, width, height, fps,
                    sampleRate, channels, attachedPicture));
        }
        long duration = formatDuration > 0 ? formatDuration : streams.stream()
                .mapToLong(Stream::durationMillis).max().orElse(-1L);
        Result result = new Result(formatName, duration, List.copyOf(streams));
        validate(result);
        return result;
    }

    private static void validate(Result result) throws IOException {
        List<Stream> videos = result.primaryVideoStreams();
        List<Stream> audios = result.audioStreams();
        if (videos.isEmpty() && audios.isEmpty()) {
            throw new IOException("文件不包含可处理的视频或音频轨道");
        }
        if (videos.size() > 1) {
            throw new IOException("当前安全版本只处理一个主视频轨道；多视频轨文件已失败关闭");
        }
        if (audios.size() > 8) {
            throw new IOException("音频轨道超过8条安全上限");
        }
        if (result.subtitleStreams().size() > 16) {
            throw new IOException("字幕轨道超过16条安全上限");
        }
        if (result.durationMillis() <= 0L) {
            throw new IOException("无法确定音视频时长，任务按失败关闭策略停止");
        }
        long maximumDuration = videos.isEmpty()
                ? boundedLongProperty("docredaction.media.maxAudioDurationMillis",
                        DEFAULT_AUDIO_MAX_MILLIS, 1_000L, 24L * 60L * 60L * 1_000L)
                : boundedLongProperty("docredaction.media.maxVideoDurationMillis",
                        DEFAULT_VIDEO_MAX_MILLIS, 1_000L, 12L * 60L * 60L * 1_000L);
        if (result.durationMillis() > maximumDuration) {
            throw new IOException("音视频时长超过本机安全上限（" + maximumDuration / 1_000L + "秒）");
        }
        for (Stream video : videos) {
            if (video.width() <= 0 || video.height() <= 0
                    || video.width() > 4_096 || video.height() > 2_160) {
                throw new IOException("视频分辨率无效或超过4096×2160上限");
            }
            if (video.framesPerSecond() <= 0.0d || video.framesPerSecond() > 60.0d) {
                throw new IOException("视频帧率无效或超过60fps上限");
            }
        }
    }

    private static long secondsToMillis(String value) {
        if (value == null || value.isBlank() || "N/A".equalsIgnoreCase(value)) {
            return -1L;
        }
        try {
            double seconds = Double.parseDouble(value);
            if (!Double.isFinite(seconds) || seconds <= 0.0d) {
                return -1L;
            }
            return Math.round(seconds * 1_000.0d);
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    private static double parseRate(String value) {
        if (value == null || value.isBlank() || "N/A".equalsIgnoreCase(value)) {
            return -1.0d;
        }
        int slash = value.indexOf('/');
        try {
            if (slash < 0) {
                return Double.parseDouble(value);
            }
            double numerator = Double.parseDouble(value.substring(0, slash));
            double denominator = Double.parseDouble(value.substring(slash + 1));
            return denominator == 0.0d ? -1.0d : numerator / denominator;
        } catch (NumberFormatException ex) {
            return -1.0d;
        }
    }

    private static int parseInteger(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
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

    record Result(String formatName, long durationMillis, List<Stream> streams) {
        List<Stream> primaryVideoStreams() {
            return streams.stream().filter(stream -> "video".equals(stream.type())
                    && !stream.attachedPicture()).toList();
        }

        List<Stream> audioStreams() {
            return streams.stream().filter(stream -> "audio".equals(stream.type())).toList();
        }

        List<Stream> subtitleStreams() {
            return streams.stream().filter(stream -> "subtitle".equals(stream.type())).toList();
        }

        List<Stream> removedStreams() {
            return streams.stream().filter(stream -> !"video".equals(stream.type())
                    && !"audio".equals(stream.type()) && !"subtitle".equals(stream.type())).toList();
        }
    }

    record Stream(int index, String type, String codec, long durationMillis,
            int width, int height, double framesPerSecond, int sampleRate,
            int channels, boolean attachedPicture) {
    }
}

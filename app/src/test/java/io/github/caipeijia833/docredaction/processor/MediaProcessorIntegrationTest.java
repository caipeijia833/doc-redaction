/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import io.github.caipeijia833.docredaction.rules.RuleEngine;
import io.github.caipeijia833.docredaction.server.JobRecord;
import io.github.caipeijia833.docredaction.server.JobService;
import io.github.caipeijia833.docredaction.server.JobStatus;
import io.github.caipeijia833.docredaction.server.JobStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.time.Duration;
import java.time.Instant;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaProcessorIntegrationTest {
    private static final List<String> MEDIA_PROPERTIES = List.of(
            "docredaction.media.ffmpeg", "docredaction.media.ffprobe",
            "docredaction.media.whisper", "docredaction.media.whisperModel",
            "docredaction.media.faceModel", "docredaction.media.plateModel");

    @TempDir
    Path temporary;

    @BeforeEach
    void configureToolchain(TestInfo testInfo) {
        if (!testInfo.getTags().contains("native")) {
            return;
        }
        String root = System.getProperty("mediaTestRoot", "").trim();
        org.junit.jupiter.api.Assumptions.assumeFalse(root.isBlank(), "mediaTestRoot not configured");
        Path tools = Path.of(root).toAbsolutePath().normalize();
        System.setProperty("docredaction.media.ffmpeg", find(tools, "ffmpeg.exe").toString());
        System.setProperty("docredaction.media.ffprobe", find(tools, "ffprobe.exe").toString());
        System.setProperty("docredaction.media.whisper", find(tools, "whisper-cli.exe").toString());
        System.setProperty("docredaction.media.whisperModel", find(tools, "ggml-base-q5_1.bin").toString());
        System.setProperty("docredaction.media.faceModel", find(tools, "face_detection_yunet_2023mar.onnx").toString());
        System.setProperty("docredaction.media.plateModel", find(tools, "license_plate_detection_lpd_yunet_2023mar.onnx").toString());
        Path ocrRoot = tools.resolve("ocr-minimal-build").resolve("runtime");
        System.setProperty("docredaction.ocr.executable", ocrRoot.resolve("bin").resolve("tesseract.exe").toString());
        System.setProperty("docredaction.ocr.dataPath", ocrRoot.resolve("share").resolve("tessdata").toString());
    }

    @AfterEach
    void clearProperties() {
        MEDIA_PROPERTIES.forEach(System::clearProperty);
        System.clearProperty("docredaction.ocr.executable");
        System.clearProperty("docredaction.ocr.dataPath");
    }

    @Test
    @Tag("native")
    void mutesSensitiveSpokenPhoneAndResidualScanPasses() throws Exception {
        Path source = Path.of(System.getProperty("mediaSpeechSample", "")).toAbsolutePath().normalize();
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(source), "speech sample not configured");
        Path input = temporary.resolve("speech.wav");
        Path output = temporary.resolve("speech-redacted.wav.tmp");
        Files.copy(source, input);

        ProcessReport report = new MediaProcessor().process(input, output, RuleEngine.createDefault());

        assertTrue(Files.size(output) > 0L);
        assertTrue(report.totalMatches() > 0, "synthetic speech must trigger a sensitive rule");
        ProcessReport residual = new ProcessReport();
        ResidualScanner.verify(input, output, RuleEngine.createDefault(), residual);
        assertEquals(0, new MediaReviewInspector().inspect(output, RuleEngine.createDefault()).items().size());
    }

    @Test
    void normalizesChineseAndEnglishSpokenDigits() {
        assertTrue(MediaAnalyzer.normalizeSpokenDigits("one three eight zero zero one three eight zero zero zero")
                .contains("13800138000"));
        assertTrue(MediaAnalyzer.normalizeSpokenDigits("一 三 八 零 零 一 三 八 零 零 零")
                .contains("13800138000"));
    }

    @Test
    @Tag("native")
    void masksSensitiveTextRenderedInVideoFrames() throws Exception {
        Path source = Path.of(System.getProperty("mediaVideoSample", "")).toAbsolutePath().normalize();
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(source), "video sample not configured");
        Path input = temporary.resolve("visual.mp4");
        Path output = temporary.resolve("visual-redacted.mp4.tmp");
        Files.copy(source, input);

        ProcessReport report = new MediaProcessor().process(input, output, RuleEngine.createDefault());

        assertTrue(Files.size(output) > 0L);
        assertTrue(report.counts().getOrDefault("CN_MOBILE_PHONE", 0) > 0);
        ProcessReport residual = new ProcessReport();
        ResidualScanner.verify(input, output, RuleEngine.createDefault(), residual);
        assertEquals(0, new MediaReviewInspector().inspect(output, RuleEngine.createDefault()).items().size());
    }

    @Test
    @Tag("native")
    void detectsFacePlateAndQrUsingPinnedOfficialSamples() throws Exception {
        Path samples = Path.of(System.getProperty("opencvVisualSamples", "")).toAbsolutePath().normalize();
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(samples), "visual samples not configured");
        Path tools = Path.of(System.getProperty("mediaTestRoot")).toAbsolutePath().normalize();
        try (OpenCvMediaDetector detector = new OpenCvMediaDetector(
                find(tools, "face_detection_yunet_2023mar.onnx"),
                find(tools, "license_plate_detection_lpd_yunet_2023mar.onnx"))) {
            var faces = detector.detect(ImageIO.read(samples.resolve("largest_selfie.jpg").toFile()));
            var plates = detector.detect(ImageIO.read(samples.resolve("plate-result-1.jpg").toFile()));
            var qrs = detector.detect(ImageIO.read(samples.resolve("qr-generated.png").toFile()));
            assertTrue(faces.stream().anyMatch(box -> box.kind() == MediaFinding.Kind.FACE));
            assertTrue(plates.stream().anyMatch(box -> box.kind() == MediaFinding.Kind.LICENSE_PLATE));
            assertTrue(qrs.stream().anyMatch(box -> box.kind() == MediaFinding.Kind.QR_CODE));
        }
    }

    @Test
    @Tag("native")
    void redactsAndPreservesTextSubtitleWhileStrippingMetadata() throws Exception {
        Path source = Path.of(System.getProperty("mediaSubtitleSample", "")).toAbsolutePath().normalize();
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(source), "subtitle sample not configured");
        Path input = temporary.resolve("subtitle.mp4");
        Path output = temporary.resolve("subtitle-redacted.mp4.tmp");
        Files.copy(source, input);

        ProcessReport report = new MediaProcessor().process(input, output, RuleEngine.createDefault());

        assertTrue(report.counts().getOrDefault("CN_MOBILE_PHONE", 0) > 0);
        MediaProbe.Result result = MediaProbe.inspect(output, new MediaToolchain());
        assertEquals(1, result.subtitleStreams().size());
        ResidualScanner.verify(input, output, RuleEngine.createDefault(), new ProcessReport());
    }

    @Test
    @Tag("native")
    void mediaRunsThroughDisposableWorkerProcess() throws Exception {
        Path source = Path.of(System.getProperty("mediaSubtitleSample", "")).toAbsolutePath().normalize();
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(source), "subtitle sample not configured");
        Path data = temporary.resolve("worker-data");
        JobStore store = new JobStore(data);
        try (JobService service = new JobService(store, new ProcessorRegistry(), RuleEngine.createDefault())) {
            JobRecord job;
            try (var input = Files.newInputStream(source)) {
                job = service.accept("media-worker-test", "subtitle.mp4", input,
                        "external_irreversible", null, false, null, Files.size(source));
            }
            Instant deadline = Instant.now().plus(Duration.ofSeconds(120));
            while (Instant.now().isBefore(deadline)) {
                job = store.get(job.id()).orElseThrow();
                if (job.status() == JobStatus.COMPLETED || job.status() == JobStatus.FAILED) {
                    break;
                }
                Thread.sleep(200L);
            }
            assertEquals(JobStatus.COMPLETED, job.status(), job.toJson());
            assertTrue(Files.isRegularFile(job.outputPath()));
        }
    }

    private static Path find(Path root, String fileName) {
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> path.getFileName().toString().equalsIgnoreCase(fileName))
                    .findFirst().orElseThrow(() -> new IllegalStateException("missing test component: " + fileName));
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

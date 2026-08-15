/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.tools;

import io.github.caipeijia833.docredaction.processor.ProcessorRegistry;
import io.github.caipeijia833.docredaction.rules.RuleEngine;
import io.github.caipeijia833.docredaction.server.JobRecord;
import io.github.caipeijia833.docredaction.server.JobService;
import io.github.caipeijia833.docredaction.server.JobStatus;
import io.github.caipeijia833.docredaction.server.JobStore;
import io.github.caipeijia833.docredaction.util.JsonUtil;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

/** Runs one large-file trial through the production JobService path. */
public final class LargeFileAcceptanceRunner {
    private static final Set<JobStatus> FINISHED = Set.of(JobStatus.COMPLETED, JobStatus.FAILED,
            JobStatus.CANCELLED, JobStatus.INTERRUPTED, JobStatus.AWAITING_CONFIRMATION,
            JobStatus.AWAITING_REVIEW);

    private LargeFileAcceptanceRunner() {
    }

    public static Path run(Path input, Path dataRoot, Path reportPath) throws Exception {
        Path source = input.toAbsolutePath().normalize();
        Path data = dataRoot.toAbsolutePath().normalize();
        Path report = reportPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("压力测试输入不存在: " + source);
        }
        if (Files.size(source) > JobService.MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("压力测试输入超过1GiB产品上限");
        }
        Files.createDirectories(data);
        if (report.getParent() != null) {
            Files.createDirectories(report.getParent());
        }

        long started = System.nanoTime();
        Instant startedAt = Instant.now();
        String sourceHash = sha256(source);
        JobRecord job;
        String archiveInspection = null;
        String structure = null;
        long outputBytes = 0L;
        String outputHash = null;
        String observedStatus;
        try (JobService service = new JobService(new JobStore(data), new ProcessorRegistry(),
                RuleEngine.createDefault(data.resolve("config").resolve("rules.properties")))) {
            try (InputStream stream = new BufferedInputStream(Files.newInputStream(source), 8 * 1024 * 1024)) {
                job = service.accept("本地1GiB分层验收", source.getFileName().toString(), stream,
                        "external_irreversible", null, false, null, Files.size(source));
            }
            waitFor(service, job, timeoutMinutes());
            observedStatus = job.status().name();
            if (job.status() == JobStatus.AWAITING_CONFIRMATION) {
                archiveInspection = service.archiveInspection(job.id()).toJson();
                service.cancel(job.id());
            } else if (job.status() == JobStatus.COMPLETED && job.outputPath() != null) {
                outputBytes = Files.size(job.outputPath());
                outputHash = sha256(job.outputPath());
                structure = validateStructure(job.outputPath());
            }
        }
        long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        String json = "{\n"
                + "  \"schemaVersion\":1,\n"
                + "  \"startedAt\":" + JsonUtil.quote(startedAt.toString()) + ",\n"
                + "  \"finishedAt\":" + JsonUtil.quote(Instant.now().toString()) + ",\n"
                + "  \"input\":" + JsonUtil.quote(source.toString()) + ",\n"
                + "  \"inputBytes\":" + Files.size(source) + ",\n"
                + "  \"inputSha256\":" + JsonUtil.quote(sourceHash) + ",\n"
                + "  \"observedStatus\":" + JsonUtil.quote(observedStatus) + ",\n"
                + "  \"durationMillis\":" + durationMillis + ",\n"
                + "  \"workerMaxHeap\":" + JsonUtil.quote(System.getProperty(
                        "docredaction.worker.maxHeap", "2048m")) + ",\n"
                + "  \"outputBytes\":" + outputBytes + ",\n"
                + "  \"outputSha256\":" + JsonUtil.quote(outputHash) + ",\n"
                + "  \"structureValidation\":" + JsonUtil.quote(structure) + ",\n"
                + "  \"archiveInspection\":" + (archiveInspection == null ? "null" : archiveInspection) + ",\n"
                + "  \"job\":" + job.toJson() + "\n"
                + "}\n";
        Files.writeString(report, json, StandardCharsets.UTF_8);
        return report;
    }

    private static void waitFor(JobService service, JobRecord job, long maximumMinutes) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(maximumMinutes);
        while (!FINISHED.contains(job.status())) {
            if (System.nanoTime() >= deadline) {
                try {
                    service.cancel(job.id());
                } catch (Exception ignored) {
                    // Timeout remains authoritative.
                }
                throw new IOException("压力测试超过" + maximumMinutes + "分钟，任务已取消");
            }
            Thread.sleep(250L);
        }
    }

    private static long timeoutMinutes() {
        try {
            long value = Long.parseLong(System.getProperty("docredaction.acceptance.timeoutMinutes", "120"));
            return Math.max(1L, Math.min(24L * 60L, value));
        } catch (NumberFormatException ex) {
            return 120L;
        }
    }

    private static String validateStructure(Path output) throws Exception {
        String name = output.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) {
            try (var document = Loader.loadPDF(output.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
                return "PDF_OK pages=" + document.getNumberOfPages();
            }
        }
        if (name.endsWith(".docx")) {
            return validateOoxml(output, "word/document.xml");
        }
        if (name.endsWith(".xlsx")) {
            return validateOoxml(output, "xl/workbook.xml", "xl/worksheets/sheet1.xml");
        }
        if (name.endsWith(".pptx")) {
            return validateOoxml(output, "ppt/presentation.xml", "ppt/slides/slide1.xml");
        }
        return "FILE_EXISTS";
    }

    private static String validateOoxml(Path output, String... requiredEntries) throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        try (ZipFile zip = new ZipFile(output.toFile(), StandardCharsets.UTF_8)) {
            for (String name : requiredEntries) {
                var entry = zip.getEntry(name);
                if (entry == null) {
                    throw new IOException("OOXML输出缺少必要部件: " + name);
                }
                try (InputStream input = zip.getInputStream(entry)) {
                    factory.newSAXParser().parse(input, new org.xml.sax.helpers.DefaultHandler());
                }
            }
            return "OOXML_OK entries=" + zip.size();
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
        byte[] buffer = new byte[8 * 1024 * 1024];
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file), buffer.length)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}

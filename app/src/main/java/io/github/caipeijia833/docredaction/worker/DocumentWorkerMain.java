/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.worker;

import io.github.caipeijia833.docredaction.archive.ArchiveEntryInfo;
import io.github.caipeijia833.docredaction.archive.ArchiveInspection;
import io.github.caipeijia833.docredaction.archive.ArchiveInspector;
import io.github.caipeijia833.docredaction.archive.ArchiveProcessor;
import io.github.caipeijia833.docredaction.processor.DocumentProcessor;
import io.github.caipeijia833.docredaction.processor.ProcessReport;
import io.github.caipeijia833.docredaction.processor.ProcessorRegistry;
import io.github.caipeijia833.docredaction.processor.ResidualScanner;
import io.github.caipeijia833.docredaction.preview.PreviewResult;
import io.github.caipeijia833.docredaction.preview.PreviewService;
import io.github.caipeijia833.docredaction.review.ReviewInspection;
import io.github.caipeijia833.docredaction.review.ReviewInspector;
import io.github.caipeijia833.docredaction.review.ReviewItem;
import io.github.caipeijia833.docredaction.rules.RuleEngine;
import io.github.caipeijia833.docredaction.security.PseudonymKeyStore;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import org.apache.poi.util.IOUtils;

/** Entry point for the memory-limited local parser process. */
public final class DocumentWorkerMain {
    private DocumentWorkerMain() {
    }

    public static void main(String[] args) {
        System.setProperty("log4j2.statusLoggerLevel", "OFF");
        configureParserLimits();
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                    new FileInputStream(FileDescriptor.in)));
                DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                    new FileOutputStream(FileDescriptor.out)))) {
            try {
                WorkerRequest request = WorkerRequest.readFrom(input);
                Object result = execute(request);
                output.writeInt(WorkerRequest.RESPONSE_MAGIC);
                output.writeBoolean(true);
                writeResult(output, request.operation(), result);
            } catch (Throwable ex) {
                output.writeInt(WorkerRequest.RESPONSE_MAGIC);
                output.writeBoolean(false);
                WorkerRequest.writeString(output, safeMessage(ex));
            }
            output.flush();
        } catch (Throwable ignored) {
            // The parent detects a missing or invalid response and terminates the task.
        }
    }

    static void configureParserLimits() {
        int maximum = boundedIntProperty("docredaction.poi.maxByteArrayBytes",
                512 * 1024 * 1024, 100_000_000, 1024 * 1024 * 1024);
        IOUtils.setByteArrayMaxOverride(maximum);
    }

    private static int boundedIntProperty(String name, int fallback, int minimum, int maximum) {
        try {
            int value = Integer.parseInt(System.getProperty(name, Integer.toString(fallback)));
            return Math.max(minimum, Math.min(maximum, value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static Object execute(WorkerRequest request) throws Exception {
        if (request.input() == null || !Files.isRegularFile(request.input())) {
            throw new IllegalArgumentException("工作进程输入文件不存在");
        }
        if (request.operation() == WorkerOperation.PREVIEW) {
            return new PreviewService().preview(request.input(), request.page());
        }
        RuleEngine rules = RuleEngine.createDefault(request.ruleSettings());
        rules = rules.withSelectedCategories(request.selectedRuleCategories());
        byte[] projectKey = null;
        if ("reversible_vault".equals(request.processingMode())) {
            projectKey = new PseudonymKeyStore(request.keyDirectory()).projectKey(request.projectId());
            rules = rules.withStablePseudonyms(projectKey);
        }
        try {
            rules = rules.withAdditionalWhitelist(request.ignoredValues());
            return switch (request.operation()) {
                case PROCESS_DOCUMENT -> processDocument(request, rules);
                case PROCESS_ARCHIVE -> processArchive(request, rules);
                case INSPECT_ARCHIVE -> new ArchiveInspector().inspect(request.input());
                case INSPECT_REVIEW -> new ReviewInspector(rules).inspect(request.input());
                case PREVIEW -> throw new IllegalStateException("预览操作路由错误");
            };
        } finally {
            if (projectKey != null) {
                Arrays.fill(projectKey, (byte) 0);
            }
        }
    }

    private static ProcessReport processDocument(WorkerRequest request, RuleEngine rules) throws Exception {
        requireOutputPaths(request);
        Files.createDirectories(request.output().getParent());
        DocumentProcessor processor = new ProcessorRegistry().requireProcessor(request.input());
        ProcessReport report = processor.process(request.input(), request.output(), rules);
        ResidualScanner.verify(request.input(), request.output(), rules, report);
        return report;
    }

    private static ProcessReport processArchive(WorkerRequest request, RuleEngine rules) throws Exception {
        requireOutputPaths(request);
        Files.createDirectories(request.output().getParent());
        Files.createDirectories(request.workDirectory());
        return new ArchiveProcessor(new ProcessorRegistry(), rules).process(
                request.input(), request.output(), request.workDirectory(), request.includeUnprocessed(),
                request.archiveEntryActions());
    }

    private static void requireOutputPaths(WorkerRequest request) {
        if (request.output() == null || request.output().getParent() == null || request.workDirectory() == null) {
            throw new IllegalArgumentException("工作进程输出路径无效");
        }
    }

    private static void writeResult(DataOutputStream output, WorkerOperation operation, Object result) throws Exception {
        switch (operation) {
            case PROCESS_DOCUMENT, PROCESS_ARCHIVE -> writeReport(output, (ProcessReport) result);
            case INSPECT_ARCHIVE -> writeArchiveInspection(output, (ArchiveInspection) result);
            case INSPECT_REVIEW -> writeReviewInspection(output, (ReviewInspection) result);
            case PREVIEW -> writePreview(output, (PreviewResult) result);
        }
    }

    private static void writeReport(DataOutputStream output, ProcessReport report) throws Exception {
        output.writeInt(report.unitsProcessed());
        output.writeInt(report.counts().size());
        for (Map.Entry<String, Integer> entry : report.counts().entrySet()) {
            WorkerRequest.writeString(output, entry.getKey());
            output.writeInt(entry.getValue());
        }
        writeStrings(output, report.warnings());
    }

    private static void writeArchiveInspection(DataOutputStream output, ArchiveInspection inspection) throws Exception {
        WorkerRequest.writeString(output, inspection.format());
        output.writeLong(inspection.totalDeclaredSize());
        output.writeBoolean(inspection.rejected());
        output.writeInt(inspection.entries().size());
        for (ArchiveEntryInfo entry : inspection.entries()) {
            output.writeInt(entry.index());
            WorkerRequest.writeString(output, entry.path());
            output.writeBoolean(entry.directory());
            output.writeLong(entry.size());
            output.writeLong(entry.compressedSize());
            WorkerRequest.writeString(output, entry.category().name());
            WorkerRequest.writeString(output, entry.reason());
            output.writeBoolean(entry.safePath());
            output.writeBoolean(entry.readable());
        }
        writeStrings(output, inspection.warnings());
    }

    private static void writeReviewInspection(DataOutputStream output, ReviewInspection inspection) throws Exception {
        output.writeBoolean(inspection.truncated());
        output.writeInt(inspection.items().size());
        for (ReviewItem item : inspection.items()) {
            output.writeInt(item.id());
            WorkerRequest.writeString(output, item.ruleId());
            WorkerRequest.writeString(output, item.label());
            WorkerRequest.writeString(output, item.location());
            WorkerRequest.writeString(output, item.value());
            WorkerRequest.writeString(output, item.context());
            WorkerRequest.writeString(output, item.mediaKind());
            output.writeInt(item.streamIndex());
            output.writeLong(item.startMillis());
            output.writeLong(item.endMillis());
            output.writeInt(item.x());
            output.writeInt(item.y());
            output.writeInt(item.width());
            output.writeInt(item.height());
            output.writeDouble(item.confidence());
            WorkerRequest.writeString(output, item.ignoreKey());
        }
        writeStrings(output, inspection.warnings());
    }

    private static void writePreview(DataOutputStream output, PreviewResult preview) throws Exception {
        WorkerRequest.writeString(output, preview.contentType());
        output.writeInt(preview.bytes().length);
        output.write(preview.bytes());
    }

    private static void writeStrings(DataOutputStream output, java.util.List<String> values) throws Exception {
        output.writeInt(values.size());
        for (String value : values) {
            WorkerRequest.writeString(output, value);
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getClass().getSimpleName();
        }
        message = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}

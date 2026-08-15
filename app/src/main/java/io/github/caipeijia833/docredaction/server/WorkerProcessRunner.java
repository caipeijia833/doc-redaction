/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.server;

import io.github.caipeijia833.docredaction.archive.ArchiveCategory;
import io.github.caipeijia833.docredaction.archive.ArchiveEntryAction;
import io.github.caipeijia833.docredaction.archive.ArchiveEntryInfo;
import io.github.caipeijia833.docredaction.archive.ArchiveInspection;
import io.github.caipeijia833.docredaction.processor.ProcessReport;
import io.github.caipeijia833.docredaction.processor.WorkerTelemetry;
import io.github.caipeijia833.docredaction.preview.PreviewResult;
import io.github.caipeijia833.docredaction.review.ReviewInspection;
import io.github.caipeijia833.docredaction.review.ReviewInspector;
import io.github.caipeijia833.docredaction.review.ReviewItem;
import io.github.caipeijia833.docredaction.worker.DocumentWorkerMain;
import io.github.caipeijia833.docredaction.worker.WorkerOperation;
import io.github.caipeijia833.docredaction.worker.WorkerRequest;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Runs every untrusted document/archive parser in a disposable child JVM. */
public final class WorkerProcessRunner {
    private static final int MAX_COLLECTION_ITEMS = 20_000;
    private static final int STDERR_CAPTURE_BYTES = 64 * 1024;

    private final Path dataRoot;
    private final long timeoutMinutes;
    private final String maximumHeap;

    public WorkerProcessRunner(Path dataRoot) {
        this.dataRoot = dataRoot.toAbsolutePath().normalize();
        this.timeoutMinutes = boundedLongProperty("docredaction.worker.timeoutMinutes", 120L, 1L, 24L * 60L);
        this.maximumHeap = heapProperty();
    }

    public ProcessReport processDocument(JobRecord job, Path output, Path workDirectory,
            List<String> ignoredValues) throws IOException {
        return (ProcessReport) invoke(request(WorkerOperation.PROCESS_DOCUMENT, job, output,
                workDirectory, false, Map.of(), ignoredValues));
    }

    public ProcessReport processArchive(JobRecord job, Path output, Path workDirectory,
            boolean includeUnprocessed, Map<Integer, ArchiveEntryAction> archiveEntryActions) throws IOException {
        return (ProcessReport) invoke(request(WorkerOperation.PROCESS_ARCHIVE, job, output,
                workDirectory, includeUnprocessed, archiveEntryActions, List.of()));
    }

    public ArchiveInspection inspectArchive(JobRecord job) throws IOException {
        return (ArchiveInspection) invoke(request(WorkerOperation.INSPECT_ARCHIVE, job,
                null, null, false, Map.of(), List.of()));
    }

    public ReviewInspection inspectReview(JobRecord job) throws IOException {
        return (ReviewInspection) invoke(request(WorkerOperation.INSPECT_REVIEW, job,
                null, null, false, Map.of(), List.of()));
    }

    public PreviewResult preview(JobRecord job, Path file, int page) throws IOException {
        WorkerRequest request = new WorkerRequest(WorkerOperation.PREVIEW, file, null, null,
                dataRoot.resolve("config").resolve("rules.properties"), dataRoot.resolve("config"),
                job.projectId(), job.processingMode(), false, Map.of(), page, List.of(), job.selectedRuleCategories());
        return (PreviewResult) invoke(request);
    }

    public long timeoutMinutes() {
        return timeoutMinutes;
    }

    public String maximumHeap() {
        return maximumHeap;
    }

    private WorkerRequest request(WorkerOperation operation, JobRecord job, Path output,
            Path workDirectory, boolean includeUnprocessed,
            Map<Integer, ArchiveEntryAction> archiveEntryActions, List<String> ignoredValues) {
        return new WorkerRequest(operation, job.inputPath(), output, workDirectory,
                dataRoot.resolve("config").resolve("rules.properties"), dataRoot.resolve("config"),
                job.projectId(), job.processingMode(), includeUnprocessed, archiveEntryActions, -1, ignoredValues,
                job.selectedRuleCategories());
    }

    private Object invoke(WorkerRequest request) throws IOException {
        Path managedJobDirectory = jobDirectory(request.input());
        Path processTemporaryDirectory = managedJobDirectory.resolve("work")
                .resolve("process-tmp-" + UUID.randomUUID()).normalize();
        if (!processTemporaryDirectory.startsWith(managedJobDirectory.resolve("work"))) {
            throw new IOException("Invalid worker temporary directory");
        }
        Files.createDirectory(processTemporaryDirectory);
        List<String> command = new ArrayList<>(List.of(
                javaExecutable().toString(),
                "-Xms64m",
                "-Xmx" + maximumHeap,
                "-XX:+ExitOnOutOfMemoryError",
                "-Djava.awt.headless=true",
                "-Dfile.encoding=UTF-8",
                "-Djava.io.tmpdir=" + processTemporaryDirectory));
        String ofdClasspath = System.getProperty("docredaction.ofd.classpath", "").trim();
        if (!ofdClasspath.isBlank()) {
            command.add("-Ddocredaction.ofd.classpath=" + ofdClasspath);
        }
        copySystemProperty(command, "docredaction.ocr.executable");
        copySystemProperty(command, "docredaction.ocr.dataPath");
        copySystemProperty(command, "docredaction.ocr.timeoutSeconds");
        copySystemProperty(command, "docredaction.poi.maxByteArrayBytes");
        copySystemProperty(command, "docredaction.media.ffmpeg");
        copySystemProperty(command, "docredaction.media.ffprobe");
        copySystemProperty(command, "docredaction.media.whisper");
        copySystemProperty(command, "docredaction.media.whisperModel");
        copySystemProperty(command, "docredaction.media.faceModel");
        copySystemProperty(command, "docredaction.media.plateModel");
        copySystemProperty(command, "docredaction.media.opencvLibrary");
        copySystemProperty(command, "docredaction.media.nativeTimeoutSeconds");
        copySystemProperty(command, "docredaction.media.maxAudioDurationMillis");
        copySystemProperty(command, "docredaction.media.maxVideoDurationMillis");
        copySystemProperty(command, "docredaction.media.analysisFps");
        copySystemProperty(command, "docredaction.media.maxSampleFrames");
        copySystemProperty(command, "docredaction.media.asrThreads");
        copySystemProperty(command, "docredaction.media.videoEncoder");
        copySystemProperty(command, "docredaction.vlm.mode");
        copySystemProperty(command, "docredaction.vlm.executable");
        copySystemProperty(command, "docredaction.vlm.model");
        copySystemProperty(command, "docredaction.vlm.mmproj");
        copySystemProperty(command, "docredaction.vlm.timeoutSeconds");
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), DocumentWorkerMain.class.getName()));
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start();
        } catch (IOException | RuntimeException ex) {
            deleteTree(processTemporaryDirectory);
            throw ex;
        }

        boolean isolationRequired = Boolean.parseBoolean(
                System.getProperty("docredaction.worker.requireWindowsJob", "false"));
        WindowsJobObject jobObject = null;
        WorkerResourceMonitor resourceMonitor = null;
        try {
            jobObject = WindowsJobObject.attach(process, committedMemoryLimitBytes(), isolationRequired);
            resourceMonitor = WorkerResourceMonitor.start(managedJobDirectory);
        } catch (IOException | RuntimeException ex) {
            terminate(process);
            if (jobObject != null) {
                jobObject.close();
            }
            deleteTree(processTemporaryDirectory);
            throw ex instanceof IOException io ? io
                    : new IOException("Unable to initialize worker resource containment", ex);
        }

        ByteArrayOutputStream standardError = new ByteArrayOutputStream();
        Thread errorReader = Thread.startVirtualThread(() -> drainBounded(process.getErrorStream(), standardError));
        FutureTask<Object> response = new FutureTask<>(() -> readResponse(process.getInputStream(), request.operation()));
        Thread responseReader = Thread.startVirtualThread(response);
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(process.getOutputStream()))) {
            request.writeTo(output);
            output.flush();
        } catch (IOException ex) {
            terminate(process);
            throw new IOException("无法向资源隔离工作进程发送任务", ex);
        }

        try {
            if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                terminate(process);
                response.cancel(true);
                throw new IOException("文档解析超过" + timeoutMinutes + "分钟，工作进程已终止");
            }
            Object result = response.get(5, TimeUnit.SECONDS);
            errorReader.join(1_000);
            if (process.exitValue() != 0) {
                throw new IOException("资源隔离工作进程异常退出（代码" + process.exitValue() + "）："
                        + safeDiagnostics(standardError));
            }
            if (result instanceof ProcessReport report) {
                report.workerTelemetry(telemetry(jobObject.snapshot(), resourceMonitor.snapshot()));
            }
            return result;
        } catch (InterruptedException ex) {
            terminate(process);
            response.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("文档解析已被取消", ex);
        } catch (ExecutionException ex) {
            terminate(process);
            try {
                errorReader.join(1_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            Throwable cause = ex.getCause();
            if (!process.isAlive() && process.exitValue() != 0) {
                throw new IOException("资源隔离工作进程在返回结果前异常退出（代码" + process.exitValue() + "）："
                        + safeDiagnostics(standardError), cause);
            }
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("无法读取资源隔离工作进程结果", cause);
        } catch (java.util.concurrent.TimeoutException ex) {
            terminate(process);
            response.cancel(true);
            throw new IOException("资源隔离工作进程未返回完整结果", ex);
        } finally {
            if (process.isAlive()) {
                terminate(process);
            }
            responseReader.interrupt();
            resourceMonitor.close();
            deleteTree(processTemporaryDirectory);
            jobObject.close();
        }
    }

    private Path jobDirectory(Path input) throws IOException {
        Path projectsRoot = dataRoot.resolve("projects").toAbsolutePath().normalize();
        Path current = input.toAbsolutePath().normalize();
        while (current != null && current.startsWith(projectsRoot)) {
            if (projectsRoot.equals(current.getParent())) {
                String name = current.getFileName().toString();
                if (!name.matches("[A-Za-z0-9._-]{1,80}")) {
                    break;
                }
                return current;
            }
            current = current.getParent();
        }
        throw new IOException("Worker input is outside the managed job directory");
    }

    private WorkerTelemetry telemetry(WindowsJobObject.JobSnapshot job,
            WorkerResourceMonitor.DiskSnapshot disk) {
        return new WorkerTelemetry(job.applied(), job.required(), job.limitBytes(),
                job.peakJobCommittedBytes(), job.peakProcessCommittedBytes(),
                disk.baselineBytes(), disk.peakBytes(), disk.additionalBytes(), job.status());
    }

    private static void copySystemProperty(List<String> command, String name) {
        String value = System.getProperty(name, "").trim();
        if (!value.isBlank()) {
            command.add("-D" + name + "=" + value);
        }
    }

    private static Object readResponse(InputStream source, WorkerOperation operation) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(source))) {
            if (input.readInt() != WorkerRequest.RESPONSE_MAGIC) {
                throw new IOException("资源隔离工作进程响应协议无效");
            }
            if (!input.readBoolean()) {
                throw new IOException(WorkerRequest.readString(input));
            }
            return switch (operation) {
                case PROCESS_DOCUMENT, PROCESS_ARCHIVE -> readReport(input);
                case INSPECT_ARCHIVE -> readArchiveInspection(input);
                case INSPECT_REVIEW -> readReviewInspection(input);
                case PREVIEW -> readPreview(input);
            };
        }
    }

    private static ProcessReport readReport(DataInputStream input) throws IOException {
        ProcessReport report = new ProcessReport();
        int units = input.readInt();
        if (units < 0 || units > 10_000_000) {
            throw new IOException("工作进程处理单元数无效");
        }
        report.addUnits(units);
        int count = checkedCount(input.readInt(), MAX_COLLECTION_ITEMS);
        for (int i = 0; i < count; i++) {
            String id = WorkerRequest.readString(input);
            int matches = input.readInt();
            if (matches < 0) {
                throw new IOException("工作进程命中数无效");
            }
            report.addCount(id, matches);
        }
        for (String warning : readStrings(input)) {
            report.warning(warning);
        }
        return report;
    }

    private static ArchiveInspection readArchiveInspection(DataInputStream input) throws IOException {
        String format = WorkerRequest.readString(input);
        long totalDeclaredSize = input.readLong();
        boolean rejected = input.readBoolean();
        int count = checkedCount(input.readInt(), 10_000);
        List<ArchiveEntryInfo> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new ArchiveEntryInfo(
                    input.readInt(), WorkerRequest.readString(input), input.readBoolean(),
                    input.readLong(), input.readLong(),
                    ArchiveCategory.valueOf(WorkerRequest.readString(input)),
                    WorkerRequest.readString(input), input.readBoolean(), input.readBoolean()));
        }
        List<String> warnings = readStrings(input);
        return new ArchiveInspection(format, entries, warnings, totalDeclaredSize, rejected);
    }

    private static ReviewInspection readReviewInspection(DataInputStream input) throws IOException {
        boolean truncated = input.readBoolean();
        int count = checkedCount(input.readInt(), ReviewInspector.MAX_ITEMS);
        List<ReviewItem> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add(new ReviewItem(input.readInt(), WorkerRequest.readString(input),
                    WorkerRequest.readString(input), WorkerRequest.readString(input),
                    WorkerRequest.readString(input), WorkerRequest.readString(input),
                    WorkerRequest.readString(input), input.readInt(), input.readLong(), input.readLong(),
                    input.readInt(), input.readInt(), input.readInt(), input.readInt(),
                    input.readDouble(), WorkerRequest.readString(input)));
        }
        return new ReviewInspection(items, readStrings(input), truncated);
    }

    private static PreviewResult readPreview(DataInputStream input) throws IOException {
        String contentType = WorkerRequest.readString(input);
        int length = input.readInt();
        if (length < 0 || length > 32 * 1024 * 1024) {
            throw new IOException("本地预览结果超过32MB安全上限");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("本地预览结果不完整");
        }
        return new PreviewResult(contentType, bytes);
    }

    private static List<String> readStrings(DataInputStream input) throws IOException {
        int count = checkedCount(input.readInt(), MAX_COLLECTION_ITEMS);
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(WorkerRequest.readString(input));
        }
        return values;
    }

    private static int checkedCount(int count, int maximum) throws IOException {
        if (count < 0 || count > maximum) {
            throw new IOException("工作进程响应集合大小无效");
        }
        return count;
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toAbsolutePath().normalize();
    }

    private static void terminate(Process process) {
        process.descendants().forEach(handle -> {
            try {
                handle.destroy();
            } catch (RuntimeException ignored) {
                // Continue terminating the remaining native/JVM descendants.
            }
        });
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static void drainBounded(InputStream input, ByteArrayOutputStream captured) {
        byte[] buffer = new byte[8 * 1024];
        try (input) {
            int read;
            int retained = 0;
            while ((read = input.read(buffer)) >= 0) {
                if (retained < STDERR_CAPTURE_BYTES) {
                    int keep = Math.min(read, STDERR_CAPTURE_BYTES - retained);
                    captured.write(buffer, 0, keep);
                    retained += keep;
                }
            }
        } catch (IOException ignored) {
            // The parent response/exit status remains authoritative.
        }
    }

    private String safeDiagnostics(ByteArrayOutputStream captured) {
        String value = captured.toString(java.nio.charset.StandardCharsets.UTF_8)
                .replace(dataRoot.toString(), "<data-root>")
                .replaceAll("[\\r\\n\\t]+", " ").trim();
        if (value.isBlank()) {
            return "无可用诊断输出";
        }
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private static long boundedLongProperty(String name, long fallback, long minimum, long maximum) {
        try {
            long value = Long.parseLong(System.getProperty(name, Long.toString(fallback)));
            return Math.max(minimum, Math.min(maximum, value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String heapProperty() {
        String value = System.getProperty("docredaction.worker.maxHeap", "2048m").trim().toLowerCase();
        return value.matches("[1-9][0-9]{2,4}[mg]") ? value : "2048m";
    }

    private long committedMemoryLimitBytes() {
        String configured = System.getProperty("docredaction.worker.maxCommittedMemory", "").trim().toLowerCase();
        if (!configured.isBlank()) {
            long parsed = parseMemoryBytes(configured);
            if (parsed >= 512L * 1024 * 1024 && parsed <= 32L * 1024 * 1024 * 1024) {
                return parsed;
            }
        }
        long heapBytes = parseMemoryBytes(maximumHeap);
        long gib = 1024L * 1024 * 1024;
        return Math.min(12L * gib, Math.max(3L * gib, heapBytes + 2L * gib));
    }

    private static long parseMemoryBytes(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,4}[mg]")) {
            return -1L;
        }
        long units = Long.parseLong(value.substring(0, value.length() - 1));
        long multiplier = value.endsWith("g") ? 1024L * 1024 * 1024 : 1024L * 1024;
        try {
            return Math.multiplyExact(units, multiplier);
        } catch (ArithmeticException ex) {
            return -1L;
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // The job-level cleanup retries files still held by a terminating native child.
                }
            }
        } catch (IOException ignored) {
            // The job-level cleanup remains the final safety net.
        }
    }
}

/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.server;

import io.github.caipeijia833.docredaction.archive.ArchiveCategory;
import io.github.caipeijia833.docredaction.archive.ArchiveEntryAction;
import io.github.caipeijia833.docredaction.archive.ArchiveEntryInfo;
import io.github.caipeijia833.docredaction.archive.ArchiveInspection;
import io.github.caipeijia833.docredaction.archive.ArchivePolicy;
import io.github.caipeijia833.docredaction.processor.ProcessReport;
import io.github.caipeijia833.docredaction.processor.ProcessorRegistry;
import io.github.caipeijia833.docredaction.processor.LocalOcrEngine;
import io.github.caipeijia833.docredaction.processor.MediaCapabilityStatus;
import io.github.caipeijia833.docredaction.rules.RuleEngine;
import io.github.caipeijia833.docredaction.rules.FilenameRedactor;
import io.github.caipeijia833.docredaction.review.ReviewInspection;
import io.github.caipeijia833.docredaction.review.ReviewInspector;
import io.github.caipeijia833.docredaction.security.VaultCrypto;
import io.github.caipeijia833.docredaction.util.JsonUtil;
import io.github.caipeijia833.docredaction.util.SafeFilenames;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

public final class JobService implements AutoCloseable {
    public static final long MAX_UPLOAD_BYTES = 1024L * 1024 * 1024;
    public static final int MAX_QUEUED_JOBS = 10_000;

    public record CapacityFile(String name, long size) {
    }

    public record CapacityEstimate(long selectedBytes, long persistentBytes, long peakWorkspaceBytes,
            long requiredFreeBytes, long usableDiskBytes, long currentDataBytes, long maxDataBytes,
            long minFreeBytes, boolean archiveEstimateIncomplete, boolean dataQuotaSufficient,
            boolean diskSufficient) {
        public boolean sufficient() {
            return dataQuotaSufficient && diskSufficient;
        }
    }

    public static final class PreparedRestoreArchive implements AutoCloseable {
        private final Path path;
        private final String downloadName;
        private final StorageQuota.Reservation reservation;

        private PreparedRestoreArchive(Path path, String downloadName, StorageQuota.Reservation reservation) {
            this.path = path;
            this.downloadName = downloadName;
            this.reservation = reservation;
        }

        public Path path() {
            return path;
        }

        public String downloadName() {
            return downloadName;
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                deleteTree(path.getParent());
            } catch (IOException ex) {
                failure = ex;
            } finally {
                reservation.close();
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private final JobStore store;
    private final ProcessorRegistry registry;
    private final RuleEngine ruleEngine;
    private final ExecutorService workers;
    private final WorkerProcessRunner workerProcessRunner;
    private final StorageQuota storageQuota;
    private final LocalOcrEngine.Capability ocrCapability;
    private final MediaCapabilityStatus mediaCapability;
    private final Map<String, ArchiveInspection> archiveInspections = new ConcurrentHashMap<>();
    private final Map<String, ReviewInspection> reviewInspections = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> taskFutures = new ConcurrentHashMap<>();
    private final Object filenameAllocationLock = new Object();

    public JobService(JobStore store, ProcessorRegistry registry, RuleEngine ruleEngine) throws IOException {
        this.store = store;
        this.registry = registry;
        this.ruleEngine = ruleEngine;
        this.workerProcessRunner = new WorkerProcessRunner(store.dataRoot());
        this.storageQuota = new StorageQuota(store);
        this.ocrCapability = new LocalOcrEngine().capability();
        this.mediaCapability = MediaCapabilityStatus.inspect(ocrCapability);
        int threads = boundedIntProperty("docredaction.worker.concurrency", 1, 1, 2);
        this.workers = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUED_JOBS), Thread.ofPlatform().name("redaction-worker-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        cleanupOrphanUploads();
        recoverIncompleteJobs();
    }

    public JobRecord accept(String projectName, String originalName, InputStream input) throws IOException {
        return accept(projectName, originalName, input, "external_irreversible", null);
    }

    public JobRecord accept(String projectName, String originalName, InputStream input,
            String requestedMode, char[] passphrase) throws IOException {
        return accept(projectName, originalName, input, requestedMode, passphrase, false);
    }

    public JobRecord accept(String projectName, String originalName, InputStream input,
            String requestedMode, char[] passphrase, boolean requireReview) throws IOException {
        return accept(projectName, originalName, input, requestedMode, passphrase, requireReview, null, -1L);
    }

    public JobRecord accept(String projectName, String originalName, InputStream input,
            String requestedMode, char[] passphrase, boolean requireReview,
            String requestedProjectId, long declaredLength) throws IOException {
        return accept(projectName, originalName, input, requestedMode, passphrase, requireReview,
                requestedProjectId, declaredLength, List.of());
    }

    public JobRecord accept(String projectName, String originalName, InputStream input,
            String requestedMode, char[] passphrase, boolean requireReview,
            String requestedProjectId, long declaredLength, List<String> requestedRuleCategories) throws IOException {
        String processingMode = normalizeProcessingMode(requestedMode);
        if (declaredLength > MAX_UPLOAD_BYTES) {
            throw new IOException("文件超过1GiB上限");
        }
        if (declaredLength < -1L) {
            throw new IllegalArgumentException("文件长度参数无效");
        }
        if ("reversible_vault".equals(processingMode)
                && (passphrase == null || passphrase.length < 10 || passphrase.length > 256)) {
            if (passphrase != null) {
                Arrays.fill(passphrase, '\0');
            }
            throw new IllegalArgumentException("可还原模式口令应为10至256个字符");
        }
        String id = UUID.randomUUID().toString();
        String projectId = normalizeProjectId(requestedProjectId);
        String safeName = sanitizeFilename(originalName);
        List<String> selectedRuleCategories = normalizeRuleCategories(requestedRuleCategories);
        if (io.github.caipeijia833.docredaction.processor.MediaProbe.isSupportedFileName(safeName)
                && !mediaCapability.available()) {
            throw new IOException("本地音视频组件未就绪：" + String.join("；", mediaCapability.problems()));
        }
        long incomingBytes = declaredLength >= 0 ? declaredLength : MAX_UPLOAD_BYTES;
        long uploadReservation = "reversible_vault".equals(processingMode)
                ? saturatedMultiply(incomingBytes, 2L) : incomingBytes;
        Path directory;
        Path inputPath;
        Path temporaryUpload = null;
        UploadResult upload;
        Path vaultPath = null;
        try (StorageQuota.Reservation ignored = storageQuota.reserve("upload:" + id, uploadReservation)) {
            directory = store.createJobDirectory(id);
            inputPath = directory.resolve("original").resolve(safeName).normalize();
            if (!inputPath.startsWith(directory.resolve("original"))) {
                throw new IOException("非法文件名");
            }
            boolean archiveCandidate = isArchiveOrRecognizedRar(safeName);
            if (!archiveCandidate) {
                registry.requireProcessor(inputPath);
            }
            try {
                temporaryUpload = directory.resolve("work")
                        .resolve("upload-" + UUID.randomUUID() + ".part").normalize();
                upload = StreamingUpload.copy(input, temporaryUpload, MAX_UPLOAD_BYTES, declaredLength);
                validateMagic(temporaryUpload, safeName);
                moveAtomically(temporaryUpload, inputPath);
                temporaryUpload = null;
            } catch (IOException | RuntimeException ex) {
                if (temporaryUpload != null) {
                    Files.deleteIfExists(temporaryUpload);
                }
                Files.deleteIfExists(inputPath);
                throw ex;
            }
            try {
                if ("reversible_vault".equals(processingMode)) {
                    vaultPath = directory.resolve("vault").resolve("original.drenc");
                    VaultCrypto.encrypt(inputPath, vaultPath, passphrase == null ? null : passphrase.clone());
                }
            } catch (IOException | RuntimeException ex) {
                Files.deleteIfExists(inputPath);
                if (vaultPath != null) {
                    Files.deleteIfExists(vaultPath);
                }
                throw ex;
            }
        } finally {
            if (passphrase != null) {
                Arrays.fill(passphrase, '\0');
            }
        }
        boolean archive = isArchiveOrRecognizedRar(safeName);
        JobRecord job = new JobRecord(id, projectId, sanitizeText(projectName, 100, "未命名项目"), safeName,
                inputPath, Instant.now(), JobStatus.UPLOADED);
        job.uploaded(upload.sha256(), upload.size());
        job.securityMode(processingMode, vaultPath);
        job.reviewRequested(requireReview);
        synchronized (filenameAllocationLock) {
            job.filenamePolicy(allocateOutputName(projectId, safeName, selectedRuleCategories),
                    selectedRuleCategories);
            store.put(job);
        }
        if (archive) {
            job.inspecting(ArchivePolicy.archiveFormat(safeName));
            store.save(job);
            submit(job, () -> inspectArchive(job, directory), Math.max(job.size(), 64L * 1024 * 1024));
        } else if (requireReview) {
            job.analyzing();
            store.save(job);
            submit(job, () -> inspectForReview(job, directory), workspaceReservation(job));
        } else {
            submit(job, () -> process(job, directory), workspaceReservation(job));
        }
        return job;
    }

    public List<FilenameRedactor.Preview> previewFilenames(String requestedProjectId,
            List<String> originalNames, List<String> requestedRuleCategories) {
        if (originalNames == null || originalNames.isEmpty()) {
            throw new IllegalArgumentException("请至少提供一个文件名");
        }
        if (originalNames.size() > 1_000) {
            throw new IllegalArgumentException("单次最多预览1000个文件名");
        }
        String projectId = normalizeProjectId(requestedProjectId);
        List<String> categories = normalizeRuleCategories(requestedRuleCategories);
        RuleEngine selectedRules = ruleEngine.withSelectedCategories(categories);
        List<String> safeNames = originalNames.stream().map(JobService::sanitizeFilename).toList();
        synchronized (filenameAllocationLock) {
            Set<String> used = existingOutputNames(projectId);
            return FilenameRedactor.allocatePreviews(safeNames, selectedRules, used);
        }
    }

    public ArchiveInspection archiveInspection(String id) throws IOException {
        JobRecord job = store.get(id).orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        if (!job.isArchive()) {
            throw new IllegalArgumentException("该任务不是压缩包任务");
        }
        ArchiveInspection cached = archiveInspections.get(id);
        if (cached != null) {
            return cached;
        }
        if (job.status() == JobStatus.INSPECTING) {
            throw new IllegalStateException("压缩包仍在检查中");
        }
        ArchiveInspection inspection = workerProcessRunner.inspectArchive(job);
        archiveInspections.put(id, inspection);
        return inspection;
    }

    public JobRecord confirmArchive(String id, boolean includeUnprocessed, String confirmation) throws IOException {
        ArchiveInspection inspection = archiveInspection(id);
        Map<Integer, ArchiveEntryAction> actions = new LinkedHashMap<>();
        for (ArchiveEntryInfo entry : inspection.entries()) {
            if (entry.directory()) {
                continue;
            }
            ArchiveEntryAction action = entry.processedByDefault() ? ArchiveEntryAction.REDACT
                    : includeUnprocessed && canKeepUnprocessed(entry)
                            ? ArchiveEntryAction.KEEP_UNPROCESSED : ArchiveEntryAction.EXCLUDE;
            actions.put(entry.index(), action);
        }
        return confirmArchive(id, actions, confirmation);
    }

    public JobRecord confirmArchive(String id, Map<Integer, ArchiveEntryAction> requestedActions,
            String confirmation) throws IOException {
        JobRecord job = store.get(id).orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        synchronized (job) {
            if (job.status() != JobStatus.AWAITING_CONFIRMATION) {
                throw new IllegalStateException("压缩包任务当前不能确认处理");
            }
            ArchiveInspection inspection = archiveInspection(id);
            if (inspection.rejected()) {
                throw new IllegalStateException("压缩包未通过安全预检，不能继续处理");
            }
            Map<Integer, ArchiveEntryAction> actions = validateArchiveActions(inspection, requestedActions);
            boolean includeUnprocessed = actions.containsValue(ArchiveEntryAction.KEEP_UNPROCESSED);
            if (includeUnprocessed
                    && !ArchiveInspection.INCLUDE_UNPROCESSED_CONFIRMATION.equals(confirmation)) {
                throw new IllegalArgumentException("原样保留未处理文件需要二次确认");
            }
            job.archiveConfirmed(actions);
            job.processing();
            store.save(job);
            Path directory = job.inputPath().getParent().getParent();
            submit(job, () -> processArchive(job, directory, includeUnprocessed),
                    archiveWorkspaceReservation(job, inspection));
            return job;
        }
    }

    private static Map<Integer, ArchiveEntryAction> validateArchiveActions(ArchiveInspection inspection,
            Map<Integer, ArchiveEntryAction> requestedActions) {
        Map<Integer, ArchiveEntryAction> requested = requestedActions == null ? Map.of() : requestedActions;
        Set<Integer> known = inspection.entries().stream()
                .filter(entry -> !entry.directory()).map(ArchiveEntryInfo::index).collect(java.util.stream.Collectors.toSet());
        if (!known.containsAll(requested.keySet())) {
            throw new IllegalArgumentException("压缩包处理决策包含未知条目");
        }
        Map<Integer, ArchiveEntryAction> normalized = new LinkedHashMap<>();
        for (ArchiveEntryInfo entry : inspection.entries()) {
            if (entry.directory()) {
                continue;
            }
            ArchiveEntryAction action = requested.getOrDefault(entry.index(),
                    entry.processedByDefault() ? ArchiveEntryAction.REDACT : ArchiveEntryAction.EXCLUDE);
            if (action == null) {
                throw new IllegalArgumentException("压缩包处理决策不能为空");
            }
            if (entry.processedByDefault()) {
                if (action != ArchiveEntryAction.REDACT && action != ArchiveEntryAction.EXCLUDE) {
                    throw new IllegalArgumentException("可脱敏条目只能选择脱敏或排除");
                }
            } else if (action == ArchiveEntryAction.REDACT) {
                throw new IllegalArgumentException("该条目不支持脱敏处理");
            } else if (action == ArchiveEntryAction.KEEP_UNPROCESSED && !canKeepUnprocessed(entry)) {
                throw new IllegalArgumentException("高风险、路径不安全或不可读取条目不能原样保留");
            }
            normalized.put(entry.index(), action);
        }
        return Map.copyOf(normalized);
    }

    private static boolean canKeepUnprocessed(ArchiveEntryInfo entry) {
        return !entry.directory() && entry.safePath() && entry.readable()
                && entry.category() != ArchiveCategory.DANGEROUS;
    }

    public ReviewInspection reviewInspection(String id) throws IOException {
        JobRecord job = store.get(id).orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        if (job.status() == JobStatus.ANALYZING) {
            throw new IllegalStateException("文档仍在分析中");
        }
        if (job.isArchive()) {
            throw new IllegalArgumentException("压缩包使用独立的内容清单确认流程");
        }
        ReviewInspection cached = reviewInspections.get(id);
        if (cached != null) {
            return cached;
        }
        if (!Files.isRegularFile(job.inputPath())) {
            throw new IllegalStateException("处理前原文档已清理，不能重新生成复核清单");
        }
        ReviewInspection inspection;
        try {
            inspection = workerProcessRunner.inspectReview(job);
        } catch (Exception ex) {
            throw new IOException("无法生成复核清单", ex);
        }
        reviewInspections.put(id, inspection);
        return inspection;
    }

    public JobRecord confirmReview(String id, java.util.List<String> ignoredValues) throws IOException {
        JobRecord job = store.get(id).orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        synchronized (job) {
            if (job.status() != JobStatus.AWAITING_REVIEW) {
                throw new IllegalStateException("任务当前不在等待复核状态");
            }
            if (ignoredValues != null && ignoredValues.size() > ReviewInspector.MAX_ITEMS) {
                throw new IllegalArgumentException("忽略项数量超过复核上限");
            }
            Path directory = job.inputPath().getParent().getParent();
            job.processing();
            store.save(job);
            List<String> taskIgnoredValues = ignoredValues == null ? List.of() : List.copyOf(ignoredValues);
            submit(job, () -> process(job, directory, taskIgnoredValues), workspaceReservation(job));
            return job;
        }
    }

    public Path restore(String id, char[] passphrase) throws IOException {
        JobRecord job = store.get(id).orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        try {
            Path restored = restoreVerifiedFile(job, passphrase == null ? null : passphrase.clone());
            job.restored();
            store.save(job);
            return restored;
        } finally {
            if (passphrase != null) {
                Arrays.fill(passphrase, '\0');
            }
        }
    }

    public PreparedRestoreArchive prepareProjectRestore(String projectId, List<String> requestedJobIds,
            char[] passphrase) throws IOException {
        List<JobRecord> projectJobs = projectJobs(projectId);
        Set<String> requested = requestedJobIds == null ? Set.of() : new LinkedHashSet<>(requestedJobIds);
        if (requested.size() > MAX_QUEUED_JOBS || requested.stream().anyMatch(id -> id == null
                || !id.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) {
            throw new IllegalArgumentException("批量还原任务ID无效");
        }
        List<JobRecord> selected = projectJobs.stream()
                .filter(job -> requested.isEmpty() || requested.contains(job.id()))
                .toList();
        if (selected.isEmpty() || (!requested.isEmpty() && selected.size() != requested.size())) {
            throw new IllegalArgumentException("批量还原任务不存在或不属于该项目");
        }
        if (selected.stream().anyMatch(job -> !job.restorable())) {
            throw new IllegalStateException("批量还原只能包含可还原任务");
        }
        long totalBytes = selected.stream().mapToLong(JobRecord::size)
                .reduce(0L, JobService::saturatedAdd);
        long largestFile = selected.stream().mapToLong(JobRecord::size).max().orElse(0L);
        long reservationBytes = saturatedAdd(saturatedAdd(totalBytes, largestFile), 64L * 1024 * 1024);
        String operationId = UUID.randomUUID().toString();
        StorageQuota.Reservation reservation = storageQuota.reserve("batch-restore:" + operationId,
                reservationBytes);
        Path batchRoot = store.dataRoot().resolve("batch-restore").resolve(operationId).normalize();
        Path archive = batchRoot.resolve("restored-originals-" + projectId.substring(0, 8) + ".zip");
        boolean prepared = false;
        try {
            Files.createDirectories(batchRoot);
            Set<String> usedNames = new HashSet<>();
            List<String> manifestItems = new ArrayList<>();
            try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(archive.toFile())) {
                output.setUseZip64(org.apache.commons.compress.archivers.zip.Zip64Mode.AsNeeded);
                for (JobRecord job : selected) {
                    Path restored = null;
                    try {
                        // Verify every selected vault before committing any restore audit
                        // counters. A wrong password on a later item must not claim that
                        // earlier originals were successfully delivered.
                        restored = restoreVerifiedFile(job, passphrase == null ? null : passphrase.clone());
                        String entryName = FilenameRedactor.allocateUnique(job.originalName(), usedNames);
                        ZipArchiveEntry entry = new ZipArchiveEntry(entryName);
                        entry.setSize(Files.size(restored));
                        output.putArchiveEntry(entry);
                        Files.copy(restored, output);
                        output.closeArchiveEntry();
                        manifestItems.add("{" +
                                "\"jobId\":" + JsonUtil.quote(job.id()) + ',' +
                                "\"originalName\":" + JsonUtil.quote(job.originalName()) + ',' +
                                "\"archiveName\":" + JsonUtil.quote(entryName) + ',' +
                                "\"sha256\":" + JsonUtil.quote(job.sha256()) +
                                "}");
                    } finally {
                        if (restored != null) {
                            Files.deleteIfExists(restored);
                        }
                    }
                }
                String manifest = "{\"schema\":\"doc-redaction-batch-restore/v1\"," +
                        "\"projectId\":" + JsonUtil.quote(projectId) + ',' +
                        "\"createdAt\":" + JsonUtil.quote(Instant.now().toString()) + ',' +
                        "\"files\":[" + String.join(",", manifestItems) + "]}";
                byte[] bytes = manifest.getBytes(StandardCharsets.UTF_8);
                ZipArchiveEntry entry = new ZipArchiveEntry("_restore_manifest.json");
                entry.setSize(bytes.length);
                output.putArchiveEntry(entry);
                output.write(bytes);
                output.closeArchiveEntry();
            }
            for (JobRecord job : selected) {
                job.restored();
                store.save(job);
            }
            prepared = true;
            return new PreparedRestoreArchive(archive, archive.getFileName().toString(), reservation);
        } finally {
            if (passphrase != null) {
                Arrays.fill(passphrase, '\0');
            }
            if (!prepared) {
                deleteTree(batchRoot);
                reservation.close();
            }
        }
    }

    private Path restoreVerifiedFile(JobRecord job, char[] passphrase) throws IOException {
        Path vault = job.vaultPath();
        if (vault == null || !Files.isRegularFile(vault)) {
            throw new IllegalStateException("该任务没有可还原的加密原件");
        }
        Path work = vault.getParent().getParent().resolve("work");
        Files.createDirectories(work);
        Path restored = work.resolve("restore-" + UUID.randomUUID() + ".tmp");
        try {
            VaultCrypto.decrypt(vault, restored, passphrase);
            String restoredHash = sha256(restored);
            if (!restoredHash.equalsIgnoreCase(job.sha256())) {
                throw new IOException("还原文件哈希与导入原件不一致");
            }
            return restored;
        } catch (IOException | RuntimeException ex) {
            Files.deleteIfExists(restored);
            throw ex;
        } finally {
            if (passphrase != null) {
                Arrays.fill(passphrase, '\0');
            }
        }
    }

    public JobRecord cancel(String id) throws IOException {
        JobRecord job = store.get(id).orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        synchronized (job) {
            if (isTerminal(job.status())) {
                throw new IllegalStateException("已结束的任务不能取消");
            }
            Future<?> future = taskFutures.remove(id);
            job.cancelled("用户已取消任务");
            store.save(job);
            if (future != null) {
                future.cancel(true);
            }
            archiveInspections.remove(id);
            reviewInspections.remove(id);
            deletePlaintextInput(job);
            cleanupWorkDirectory(job);
            return job;
        }
    }

    public JobRecord retry(String id) throws IOException {
        JobRecord job = store.get(id).orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        synchronized (job) {
            if (job.status() != JobStatus.INTERRUPTED) {
                throw new IllegalStateException("只有中断且保留原文的任务可以重试");
            }
            if (!Files.isRegularFile(job.inputPath())) {
                throw new IllegalStateException("原文已不存在，不能重试；可还原任务请先执行还原");
            }
            job.retrying();
            store.save(job);
            dispatchFresh(job);
            return job;
        }
    }

    public void delete(String id) throws IOException {
        JobRecord job = store.get(id).orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        synchronized (job) {
            if (isActive(job.status())) {
                throw new IllegalStateException("任务仍在运行，请先取消后再删除");
            }
            Path projectsRoot = store.dataRoot().resolve("projects").toAbsolutePath().normalize();
            Path directory = job.inputPath().toAbsolutePath().normalize().getParent().getParent();
            if (directory.equals(projectsRoot) || !directory.startsWith(projectsRoot)) {
                throw new IOException("拒绝删除非法任务目录");
            }
            deleteTree(directory);
            archiveInspections.remove(id);
            reviewInspections.remove(id);
            store.remove(id);
        }
    }

    public List<JobRecord> projectJobs(String projectId) {
        if (projectId == null || !projectId.matches(
                "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("项目ID无效");
        }
        List<JobRecord> jobs = store.list().stream()
                .filter(job -> projectId.equalsIgnoreCase(job.projectId()))
                .sorted(Comparator.comparing(JobRecord::createdAt))
                .toList();
        if (jobs.isEmpty()) {
            throw new IllegalArgumentException("项目不存在");
        }
        return jobs;
    }

    public synchronized int deleteProject(String projectId) throws IOException {
        if (projectId == null || !projectId.matches(
                "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("项目ID无效");
        }
        List<JobRecord> jobs = store.list().stream()
                .filter(job -> projectId.equalsIgnoreCase(job.projectId()))
                .toList();
        if (jobs.isEmpty()) {
            throw new IllegalArgumentException("项目不存在");
        }
        if (jobs.stream().anyMatch(job -> isActive(job.status()))) {
            throw new IllegalStateException("项目仍有运行中的任务，请先逐项取消后再删除");
        }
        for (JobRecord job : jobs) {
            delete(job.id());
        }
        return jobs.size();
    }

    public JobStore store() {
        return store;
    }

    public ProcessorRegistry registry() {
        return registry;
    }

    public StorageQuota storageQuota() {
        return storageQuota;
    }

    public WorkerProcessRunner workerProcessRunner() {
        return workerProcessRunner;
    }

    public LocalOcrEngine.Capability ocrCapability() {
        return ocrCapability;
    }

    public MediaCapabilityStatus mediaCapability() {
        return mediaCapability;
    }

    public CapacityEstimate estimateCapacity(List<CapacityFile> files, String requestedMode) throws IOException {
        if (files == null || files.isEmpty() || files.size() > MAX_QUEUED_JOBS) {
            throw new IllegalArgumentException("容量评估文件数量无效");
        }
        boolean reversible = "reversible_vault".equals(normalizeProcessingMode(requestedMode));
        long selected = 0L;
        long persistent = 0L;
        long peakWorkspace = 0L;
        boolean archiveIncomplete = false;
        for (CapacityFile file : files) {
            if (file == null || file.name() == null || file.name().isBlank()
                    || file.name().length() > 512 || file.size() < 0 || file.size() > MAX_UPLOAD_BYTES) {
                throw new IllegalArgumentException("容量评估文件参数无效");
            }
            selected = saturatedAdd(selected, file.size());
            persistent = saturatedAdd(persistent,
                    reversible ? saturatedMultiply(file.size(), 2L) : file.size());
            long workspace;
            if (ArchivePolicy.isSupportedArchiveName(file.name())) {
                archiveIncomplete = true;
                workspace = Math.max(saturatedMultiply(file.size(), 3L), 256L * 1024 * 1024);
            } else {
                workspace = workspaceReservation(file.name(), file.size());
            }
            peakWorkspace = Math.max(peakWorkspace, workspace);
        }
        long workload = saturatedAdd(persistent, peakWorkspace);
        long requiredFree = saturatedAdd(workload, storageQuota.minFreeBytes());
        long usableDisk = storageQuota.usableDiskBytes();
        long currentData = storageQuota.currentDataBytes();
        boolean dataSufficient = !exceeds(currentData, workload, storageQuota.maxDataBytes());
        boolean diskSufficient = requiredFree <= usableDisk;
        return new CapacityEstimate(selected, persistent, peakWorkspace, requiredFree, usableDisk,
                currentData, storageQuota.maxDataBytes(), storageQuota.minFreeBytes(), archiveIncomplete,
                dataSufficient, diskSufficient);
    }

    private void process(JobRecord job, Path directory) {
        process(job, directory, List.of());
    }

    private void process(JobRecord job, Path directory, List<String> ignoredValues) {
        try {
            job.processing();
            store.save(job);
            String outputName = job.redactedName();
            Path finalOutput = directory.resolve("output").resolve(outputName);
            Path temporaryOutput = directory.resolve("work").resolve(outputName + ".tmp");
            ProcessReport report = workerProcessRunner.processDocument(job, temporaryOutput,
                    directory.resolve("work").resolve("worker"), ignoredValues);
            if (isStopped(job)) {
                Files.deleteIfExists(temporaryOutput);
                return;
            }
            try {
                Files.move(temporaryOutput, finalOutput, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                Files.move(temporaryOutput, finalOutput, StandardCopyOption.REPLACE_EXISTING);
            }
            deletePlaintextInput(job);
            job.completed(finalOutput, report);
            store.save(job);
        } catch (Throwable ex) {
            if (!isStopped(job)) {
                job.failed(ex);
                deletePlaintextInput(job);
                cleanupWorkDirectory(job);
                try {
                    store.save(job);
                } catch (IOException ignored) {
                    // The original processing error remains the primary failure.
                }
            }
        } finally {
            if (job.status() != JobStatus.INTERRUPTED) {
                deletePlaintextInput(job);
            }
        }
    }

    private void inspectForReview(JobRecord job, Path directory) {
        try {
            ReviewInspection inspection = workerProcessRunner.inspectReview(job);
            if (isStopped(job)) {
                return;
            }
            reviewInspections.put(job.id(), inspection);
            job.awaitingReview();
            store.save(job);
        } catch (Throwable ex) {
            if (!isStopped(job)) {
                job.failed(ex);
                deletePlaintextInput(job);
                cleanupWorkDirectory(job);
                try {
                    store.save(job);
                } catch (IOException ignored) {
                    // The inspection error remains primary.
                }
            }
        }
    }

    private void inspectArchive(JobRecord job, Path directory) {
        try {
            ArchiveInspection inspection = workerProcessRunner.inspectArchive(job);
            if (isStopped(job)) {
                return;
            }
            archiveInspections.put(job.id(), inspection);
            job.awaitingConfirmation(inspection.format());
            store.save(job);
        } catch (Throwable ex) {
            if (!isStopped(job)) {
                job.failed(ex);
                deletePlaintextInput(job);
                cleanupWorkDirectory(job);
                try {
                    store.save(job);
                } catch (IOException ignored) {
                    // The inspection error remains primary.
                }
            }
        }
    }

    private void processArchive(JobRecord job, Path directory, boolean includeUnprocessed) {
        try {
            job.processing();
            store.save(job);
            String outputName = job.redactedName();
            Path finalOutput = directory.resolve("output").resolve(outputName);
            Path temporaryOutput = directory.resolve("work").resolve(outputName + ".tmp");
            Files.deleteIfExists(temporaryOutput);
            ProcessReport report = workerProcessRunner.processArchive(job, temporaryOutput,
                    directory.resolve("work").resolve("archive-entries"), includeUnprocessed,
                    job.archiveEntryActions());
            if (isStopped(job)) {
                Files.deleteIfExists(temporaryOutput);
                return;
            }
            try {
                Files.move(temporaryOutput, finalOutput, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                Files.move(temporaryOutput, finalOutput, StandardCopyOption.REPLACE_EXISTING);
            }
            deletePlaintextInput(job);
            job.completed(finalOutput, report);
            store.save(job);
        } catch (Throwable ex) {
            if (!isStopped(job)) {
                job.failed(ex);
                cleanupWorkDirectory(job);
                try {
                    store.save(job);
                } catch (IOException ignored) {
                    // The processing error remains primary.
                }
            }
        } finally {
            if (job.status() != JobStatus.INTERRUPTED) {
                deletePlaintextInput(job);
            }
        }
    }

    private void recoverIncompleteJobs() {
        for (JobRecord job : store.list()) {
            JobStatus previous = job.status();
            if (!isActive(previous)) {
                continue;
            }
            if (!Files.isRegularFile(job.inputPath())) {
                job.interrupted("应用重启后未找到待处理原文，任务未自动恢复");
                saveQuietly(job);
                continue;
            }
            try {
                Path directory = job.inputPath().getParent().getParent();
                if (previous == JobStatus.INSPECTING) {
                    submit(job, () -> inspectArchive(job, directory), Math.max(job.size(), 64L * 1024 * 1024));
                } else if (previous == JobStatus.ANALYZING) {
                    submit(job, () -> inspectForReview(job, directory), workspaceReservation(job));
                } else if (previous == JobStatus.PROCESSING && job.isArchive()) {
                    ArchiveInspection inspection = workerProcessRunner.inspectArchive(job);
                    archiveInspections.put(job.id(), inspection);
                    submit(job, () -> processArchive(job, directory, job.includeUnprocessed()),
                            archiveWorkspaceReservation(job, inspection));
                } else if (previous == JobStatus.PROCESSING && job.reviewRequested()) {
                    job.analyzing();
                    store.save(job);
                    submit(job, () -> inspectForReview(job, directory), workspaceReservation(job));
                } else if (previous == JobStatus.UPLOADED) {
                    dispatchFresh(job);
                } else {
                    submit(job, () -> process(job, directory), workspaceReservation(job));
                }
            } catch (IOException | RuntimeException ex) {
                job.interrupted("任务自动恢复失败：" + safeReason(ex));
                saveQuietly(job);
            }
        }
    }

    private void dispatchFresh(JobRecord job) throws IOException {
        Path directory = job.inputPath().getParent().getParent();
        if (job.isArchive() || isArchiveOrRecognizedRar(job.originalName())) {
            job.inspecting(ArchivePolicy.archiveFormat(job.originalName()));
            store.save(job);
            submit(job, () -> inspectArchive(job, directory), Math.max(job.size(), 64L * 1024 * 1024));
        } else if (job.reviewRequested()) {
            job.analyzing();
            store.save(job);
            submit(job, () -> inspectForReview(job, directory), workspaceReservation(job));
        } else {
            submit(job, () -> process(job, directory), workspaceReservation(job));
        }
    }

    static long workspaceReservation(JobRecord job) {
        return workspaceReservation(job.originalName(), job.size());
    }

    static long workspaceReservation(String originalName, long size) {
        String name = originalName.toLowerCase(Locale.ROOT);
        long minimum;
        long factor;
        if (name.endsWith(".pdf") || name.endsWith(".ofd")) {
            minimum = 2L * 1024 * 1024 * 1024;
            factor = 6L;
        } else if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".bmp")) {
            minimum = 512L * 1024 * 1024;
            factor = 4L;
        } else if (name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".mkv")) {
            minimum = 4L * 1024 * 1024 * 1024;
            factor = 5L;
        } else if (name.endsWith(".mp3") || name.endsWith(".wav")
                || name.endsWith(".m4a") || name.endsWith(".flac")) {
            minimum = 2L * 1024 * 1024 * 1024;
            factor = 4L;
        } else {
            minimum = 256L * 1024 * 1024;
            factor = 3L;
        }
        return Math.max(saturatedMultiply(size, factor), minimum);
    }

    private static long archiveWorkspaceReservation(JobRecord job, ArchiveInspection inspection) {
        long expanded = saturatedMultiply(inspection.totalDeclaredSize(), 2L);
        return Math.max(saturatedAdd(job.size(), expanded), 256L * 1024 * 1024);
    }

    private static long saturatedMultiply(long value, long factor) {
        try {
            return Math.multiplyExact(Math.max(0L, value), factor);
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatedAdd(long first, long second) {
        try {
            return Math.addExact(Math.max(0L, first), Math.max(0L, second));
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }

    private static boolean exceeds(long first, long second, long limit) {
        try {
            return Math.addExact(Math.max(0L, first), Math.max(0L, second)) > limit;
        } catch (ArithmeticException ex) {
            return true;
        }
    }

    private static boolean isActive(JobStatus status) {
        return status == JobStatus.UPLOADED || status == JobStatus.INSPECTING
                || status == JobStatus.ANALYZING || status == JobStatus.PROCESSING;
    }

    private static boolean isTerminal(JobStatus status) {
        return status == JobStatus.COMPLETED || status == JobStatus.FAILED
                || status == JobStatus.CANCELLED || status == JobStatus.INTERRUPTED;
    }

    private static boolean isStopped(JobRecord job) {
        return job.status() == JobStatus.CANCELLED || job.status() == JobStatus.INTERRUPTED
                || Thread.currentThread().isInterrupted();
    }

    private static String safeReason(Throwable throwable) {
        String value = throwable.getMessage();
        if (value == null || value.isBlank()) {
            value = throwable.getClass().getSimpleName();
        }
        value = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return value.length() > 160 ? value.substring(0, 160) : value;
    }

    private void saveQuietly(JobRecord job) {
        try {
            store.save(job);
        } catch (IOException ignored) {
            // Recovery continues so one corrupt entry cannot prevent startup.
        }
    }

    private void submit(JobRecord job, Runnable task, long reservationBytes) throws IOException {
        StorageQuota.Reservation reservation = storageQuota.reserve("task:" + job.id(), reservationBytes);
        FutureTask<Void> future = new FutureTask<>(() -> {
            try {
                task.run();
            } finally {
                reservation.close();
                taskFutures.remove(job.id());
            }
            return null;
        });
        if (taskFutures.putIfAbsent(job.id(), future) != null) {
            reservation.close();
            throw new IllegalStateException("任务已经在执行");
        }
        try {
            workers.execute(future);
        } catch (RejectedExecutionException ex) {
            taskFutures.remove(job.id(), future);
            reservation.close();
            job.failed(new IOException("本地任务队列已满（最多等待" + MAX_QUEUED_JOBS + "项），请稍后重试"));
            store.save(job);
            throw new IOException("本地任务队列已满（最多等待" + MAX_QUEUED_JOBS + "项），请稍后重试");
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
        byte[] buffer = new byte[1024 * 1024];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void validateMagic(Path file, String originalName) throws IOException {
        byte[] header = new byte[512];
        int read;
        try (InputStream input = Files.newInputStream(file)) {
            read = input.read(header);
        }
        String name = originalName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) {
            if (read < 5 || header[0] != '%' || header[1] != 'P' || header[2] != 'D'
                    || header[3] != 'F' || header[4] != '-') {
                throw new IOException("PDF文件头校验失败");
            }
        } else if (name.endsWith(".docx") || name.endsWith(".xlsx") || name.endsWith(".pptx")
                || name.endsWith(".ofd")
                || name.endsWith(".zip")) {
            if (read < 4 || header[0] != 'P' || header[1] != 'K') {
                throw new IOException("Office或ZIP文件头校验失败");
            }
        } else if (name.endsWith(".png")) {
            byte[] expected = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
            if (read < expected.length || !startsWith(header, expected)) {
                throw new IOException("PNG文件头校验失败");
            }
        } else if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            if (read < 3 || header[0] != (byte) 0xFF || header[1] != (byte) 0xD8
                    || header[2] != (byte) 0xFF) {
                throw new IOException("JPEG文件头校验失败");
            }
        } else if (name.endsWith(".bmp")) {
            if (read < 2 || header[0] != 'B' || header[1] != 'M') {
                throw new IOException("BMP文件头校验失败");
            }
        } else if (name.endsWith(".wav")) {
            if (read < 12 || header[0] != 'R' || header[1] != 'I' || header[2] != 'F'
                    || header[3] != 'F' || header[8] != 'W' || header[9] != 'A'
                    || header[10] != 'V' || header[11] != 'E') {
                throw new IOException("WAV文件头校验失败");
            }
        } else if (name.endsWith(".flac")) {
            if (read < 4 || header[0] != 'f' || header[1] != 'L'
                    || header[2] != 'a' || header[3] != 'C') {
                throw new IOException("FLAC文件头校验失败");
            }
        } else if (name.endsWith(".mp3")) {
            boolean id3 = read >= 3 && header[0] == 'I' && header[1] == 'D' && header[2] == '3';
            boolean frameSync = read >= 2 && header[0] == (byte) 0xFF && (header[1] & 0xE0) == 0xE0;
            if (!id3 && !frameSync) {
                throw new IOException("MP3文件头校验失败");
            }
        } else if (name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".m4a")) {
            if (read < 12 || header[4] != 'f' || header[5] != 't'
                    || header[6] != 'y' || header[7] != 'p') {
                throw new IOException("MP4/MOV/M4A文件头校验失败");
            }
        } else if (name.endsWith(".mkv")) {
            if (read < 4 || header[0] != 0x1A || header[1] != 0x45
                    || header[2] != (byte) 0xDF || header[3] != (byte) 0xA3) {
                throw new IOException("MKV文件头校验失败");
            }
        } else if (name.endsWith(".7z")) {
            byte[] expected = {0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C};
            if (read < expected.length) {
                throw new IOException("7Z文件头校验失败");
            }
            for (int i = 0; i < expected.length; i++) {
                if (header[i] != expected[i]) {
                    throw new IOException("7Z文件头校验失败");
                }
            }
        } else if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            if (read < 2 || header[0] != 0x1F || header[1] != (byte) 0x8B) {
                throw new IOException("GZIP文件头校验失败");
            }
        } else if (name.endsWith(".tar")) {
            if (read < 265 || header[257] != 'u' || header[258] != 's' || header[259] != 't'
                    || header[260] != 'a' || header[261] != 'r') {
                throw new IOException("TAR文件头校验失败");
            }
        } else if (name.endsWith(".rar")) {
            if (read < 7 || header[0] != 'R' || header[1] != 'a' || header[2] != 'r'
                    || header[3] != '!' || header[4] != 0x1A || header[5] != 0x07) {
                throw new IOException("RAR文件头校验失败");
            }
        }
    }

    private static boolean startsWith(byte[] source, byte[] expected) {
        for (int i = 0; i < expected.length; i++) {
            if (source[i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            Files.move(source, target);
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

    private static String normalizeProjectId(String requestedProjectId) {
        if (requestedProjectId == null || requestedProjectId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        try {
            return UUID.fromString(requestedProjectId.trim()).toString();
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("项目编号必须是有效的UUID");
        }
    }

    private static void cleanupWorkDirectory(JobRecord job) {
        Path directory = job.inputPath().getParent().getParent().resolve("work").normalize();
        try {
            if (Files.isDirectory(directory)) {
                try (var children = Files.list(directory)) {
                    for (Path child : children.toList()) {
                        deleteTree(child);
                    }
                }
            }
        } catch (IOException ignored) {
            // Locked files are retried when the task is deleted.
        }
    }

    private void cleanupOrphanUploads() throws IOException {
        Path projectsRoot = store.dataRoot().resolve("projects").toAbsolutePath().normalize();
        if (!Files.isDirectory(projectsRoot)) {
            return;
        }
        try (var paths = Files.walk(projectsRoot, 3)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                Path normalized = path.toAbsolutePath().normalize();
                String filename = normalized.getFileName().toString();
                if (normalized.startsWith(projectsRoot) && filename.startsWith("upload-")
                        && filename.endsWith(".part")
                        && normalized.getParent() != null
                        && "work".equalsIgnoreCase(normalized.getParent().getFileName().toString())) {
                    Files.deleteIfExists(normalized);
                }
            }
        }
    }

    private static void deleteTree(Path target) throws IOException {
        if (!Files.exists(target)) {
            return;
        }
        try (var paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String sanitizeFilename(String value) {
        return SafeFilenames.sanitizeLeafName(value);
    }

    private static String sanitizeText(String value, int maxLength, String fallback) {
        String text = value == null ? "" : value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").trim();
        if (text.isBlank()) {
            return fallback;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private String allocateOutputName(String projectId, String originalName, List<String> categories) {
        RuleEngine selectedRules = ruleEngine.withSelectedCategories(categories);
        String candidate = FilenameRedactor.previewOutputName(originalName, selectedRules).redactedName();
        return FilenameRedactor.allocateUnique(candidate, existingOutputNames(projectId));
    }

    private Set<String> existingOutputNames(String projectId) {
        Set<String> names = new HashSet<>();
        for (JobRecord existing : store.list()) {
            if (existing.projectId().equals(projectId)) {
                names.add(existing.redactedName().toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private List<String> normalizeRuleCategories(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        Set<String> known = ruleEngine.rules().stream()
                .map(rule -> rule.category().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String value : requested) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String category = value.trim().toLowerCase(Locale.ROOT);
            if (!category.matches("[a-z0-9_]{1,40}") || !known.contains(category)) {
                throw new IllegalArgumentException("规则分类不存在：" + category);
            }
            selected.add(category);
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("至少选择一个脱敏规则分类");
        }
        return List.copyOf(selected);
    }

    private static boolean isArchiveOrRecognizedRar(String name) {
        return ArchivePolicy.isSupportedArchiveName(name)
                || name.toLowerCase(Locale.ROOT).endsWith(".rar");
    }

    private static String normalizeProcessingMode(String mode) {
        String value = mode == null ? "external_irreversible" : mode.trim().toLowerCase(Locale.ROOT);
        if (!value.equals("external_irreversible") && !value.equals("reversible_vault")) {
            throw new IllegalArgumentException("处理模式无效");
        }
        return value;
    }

    private static void deletePlaintextInput(JobRecord job) {
        try {
            Files.deleteIfExists(job.inputPath());
        } catch (IOException ignored) {
            // A later cleanup pass may remove a locked plaintext input.
        }
    }

    @Override
    public void close() {
        for (String id : taskFutures.keySet()) {
            store.get(id).ifPresent(job -> {
                if (isActive(job.status())) {
                    job.interrupted("应用关闭，任务已安全中断，可在下次启动后重试");
                    saveQuietly(job);
                }
            });
        }
        taskFutures.values().forEach(future -> future.cancel(true));
        workers.shutdownNow();
        try {
            // Do not return while a final state/audit write can still hold the
            // SQLite file. This also makes removable/offline package shutdown
            // deterministic instead of relying on process exit to release it.
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException ex) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

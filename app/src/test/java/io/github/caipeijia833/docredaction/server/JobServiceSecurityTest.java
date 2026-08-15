/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.server;

import io.github.caipeijia833.docredaction.archive.ArchiveEntryAction;
import io.github.caipeijia833.docredaction.archive.ArchiveInspection;
import io.github.caipeijia833.docredaction.processor.ProcessorRegistry;
import io.github.caipeijia833.docredaction.rules.RuleEngine;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobServiceSecurityTest {
    @TempDir
    Path temp;

    @Test
    void reversibleModeEncryptsOriginalDeletesPlaintextAndRestoresExactBytes() throws Exception {
        byte[] original = syntheticDocx();
        JobStore store = new JobStore(temp.resolve("data"));
        try (JobService service = new JobService(store, new ProcessorRegistry(), RuleEngine.createDefault())) {
            JobRecord job = service.accept("稳定映射项目", "合成原件.docx",
                    new ByteArrayInputStream(original), "reversible_vault", "local-passphrase".toCharArray());

            for (int attempt = 0; attempt < 100 && job.status() != JobStatus.COMPLETED
                    && job.status() != JobStatus.FAILED; attempt++) {
                Thread.sleep(50);
            }

            assertEquals(JobStatus.COMPLETED, job.status());
            assertTrue(job.restorable());
            assertTrue(Files.isRegularFile(job.vaultPath()));
            assertFalse(Files.exists(job.inputPath()));

            Path restored = service.restore(job.id(), "local-passphrase".toCharArray());
            assertArrayEquals(original, Files.readAllBytes(restored));
            assertEquals(1, store.get(job.id()).orElseThrow().toJson().contains("\"restoreCount\":1") ? 1 : 0);
            Files.deleteIfExists(restored);
        }
    }

    @Test
    void redactsOutputFilenameAndRestoresOriginalFilenameMetadata() throws Exception {
        byte[] original = syntheticDocx();
        JobStore store = new JobStore(temp.resolve("filename-data"));
        try (JobService service = new JobService(store, new ProcessorRegistry(), RuleEngine.createDefault())) {
            JobRecord job = service.accept("文件名项目", "手机号13800138000.docx",
                    new ByteArrayInputStream(original), "reversible_vault", "local-passphrase".toCharArray(),
                    false, "12121212-1212-1212-1212-121212121212", original.length,
                    List.of("contact_location"));
            waitFor(job, JobStatus.COMPLETED);

            assertEquals("手机号13800138000.docx", job.originalName());
            assertFalse(job.redactedName().contains("13800138000"));
            assertTrue(job.redactedName().endsWith("_脱敏.docx"));
            assertEquals(job.redactedName(), job.outputPath().getFileName().toString());
            assertTrue(job.toJson().contains("\"selectedRuleCategories\":[\"contact_location\"]"));
        }
    }

    @Test
    void failedBatchRestoreDoesNotIncrementAnyAuditCounter() throws Exception {
        byte[] original = syntheticDocx();
        String projectId = "23232323-2323-2323-2323-232323232323";
        JobStore store = new JobStore(temp.resolve("batch-restore-audit-data"));
        try (JobService service = new JobService(store, new ProcessorRegistry(), RuleEngine.createDefault())) {
            JobRecord older = service.accept("批量还原审计", "older.docx", new ByteArrayInputStream(original),
                    "reversible_vault", "older-password".toCharArray(), false, projectId, original.length);
            JobRecord newer = service.accept("批量还原审计", "newer.docx", new ByteArrayInputStream(original),
                    "reversible_vault", "newer-password".toCharArray(), false, projectId, original.length);
            waitFor(older, JobStatus.COMPLETED);
            waitFor(newer, JobStatus.COMPLETED);

            assertThrows(IOException.class, () -> service.prepareProjectRestore(projectId,
                    List.of(older.id(), newer.id()), "newer-password".toCharArray()));

            assertEquals(0, older.restoreCount());
            assertEquals(0, newer.restoreCount());
        }
    }

    @Test
    void reviewFlowAllowsTaskScopedIgnoreWithoutChangingGlobalRules() throws Exception {
        byte[] original;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("手机号13800138000，邮箱review@example.com");
            document.write(output);
            original = output.toByteArray();
        }
        JobStore store = new JobStore(temp.resolve("review-data"));
        RuleEngine rules = RuleEngine.createDefault();
        try (JobService service = new JobService(store, new ProcessorRegistry(), rules)) {
            JobRecord job = service.accept("人工复核项目", "复核.docx", new ByteArrayInputStream(original),
                    "external_irreversible", null, true);
            waitFor(job, JobStatus.AWAITING_REVIEW);
            var inspection = service.reviewInspection(job.id());
            assertTrue(inspection.items().stream().anyMatch(item -> item.value().equals("13800138000")));
            assertTrue(inspection.items().stream().anyMatch(item -> item.value().equals("review@example.com")));
            assertFalse(Files.exists(job.inputPath().getParent().getParent()
                    .resolve("work").resolve("review-inspection.json")));

            service.confirmReview(job.id(), List.of("13800138000"));
            waitFor(job, JobStatus.COMPLETED);

            try (XWPFDocument document = new XWPFDocument(Files.newInputStream(job.outputPath()))) {
                String text = document.getParagraphs().getFirst().getText();
                assertTrue(text.contains("13800138000"));
                assertFalse(text.contains("review@example.com"));
            }
            assertTrue(rules.detect("手机13800138000").stream()
                    .anyMatch(match -> match.ruleId().equals("CN_MOBILE_PHONE")));
            assertFalse(Files.exists(job.inputPath()));
        }
    }

    @Test
    void cancelWaitingReviewDeletesPlaintextAndExplicitDeleteRemovesHistory() throws Exception {
        byte[] original = syntheticDocx();
        JobStore store = new JobStore(temp.resolve("cancel-data"));
        try (JobService service = new JobService(store, new ProcessorRegistry(), RuleEngine.createDefault())) {
            JobRecord job = service.accept("取消项目", "cancel.docx", new ByteArrayInputStream(original),
                    "external_irreversible", null, true,
                    "33333333-3333-3333-3333-333333333333", original.length);
            waitFor(job, JobStatus.AWAITING_REVIEW);

            service.cancel(job.id());
            assertEquals(JobStatus.CANCELLED, job.status());
            assertFalse(Files.exists(job.inputPath()));

            service.delete(job.id());
            assertTrue(store.get(job.id()).isEmpty());
            assertFalse(Files.exists(job.inputPath().getParent().getParent()));
        }
    }

    @Test
    void startupMarksActiveJobWithoutInputAsInterrupted() throws Exception {
        JobStore store = new JobStore(temp.resolve("recovery-data"));
        Path directory = store.createJobDirectory("recovery-job");
        JobRecord job = new JobRecord("recovery-job", "44444444-4444-4444-4444-444444444444",
                "恢复项目", "missing.docx", directory.resolve("original").resolve("missing.docx"),
                Instant.now(), JobStatus.PROCESSING);
        store.put(job);

        try (JobService ignored = new JobService(store, new ProcessorRegistry(), RuleEngine.createDefault())) {
            assertEquals(JobStatus.INTERRUPTED, job.status());
            assertTrue(job.toJson().contains("未找到待处理原文"));
        }
    }

    @Test
    void deletesCompletedProjectAsOneMaintenanceOperation() throws Exception {
        byte[] original = syntheticDocx();
        String projectId = "55555555-5555-5555-5555-555555555555";
        JobStore store = new JobStore(temp.resolve("project-delete-data"));
        try (JobService service = new JobService(store, new ProcessorRegistry(), RuleEngine.createDefault())) {
            JobRecord first = service.accept("批量卷宗项目", "one.docx", new ByteArrayInputStream(original),
                    "external_irreversible", null, false, projectId, original.length);
            JobRecord second = service.accept("批量卷宗项目", "two.docx", new ByteArrayInputStream(original),
                    "external_irreversible", null, false, projectId, original.length);
            waitFor(first, JobStatus.COMPLETED);
            waitFor(second, JobStatus.COMPLETED);

            assertEquals(2, service.deleteProject(projectId));
            assertTrue(store.list().isEmpty());
            assertFalse(Files.exists(first.inputPath().getParent().getParent()));
            assertFalse(Files.exists(second.inputPath().getParent().getParent()));
        }
    }

    @Test
    void rejectsDeclaredUploadAboveOneGibBeforeReadingBody() throws Exception {
        JobStore store = new JobStore(temp.resolve("upload-limit-data"));
        try (JobService service = new JobService(store, new ProcessorRegistry(), RuleEngine.createDefault())) {
            InputStream mustNotBeRead = new InputStream() {
                @Override
                public int read() {
                    throw new AssertionError("oversized request body must not be read");
                }
            };
            IOException error = assertThrows(IOException.class, () -> service.accept(
                    "上限验证", "large.docx", mustNotBeRead, "external_irreversible", null,
                    false, "66666666-6666-6666-6666-666666666666", JobService.MAX_UPLOAD_BYTES + 1));
            assertTrue(error.getMessage().contains("1GiB"));
            assertTrue(store.list().isEmpty());
        }
    }

    @Test
    void startupDeletesOnlyOrphanUploadParts() throws Exception {
        JobStore store = new JobStore(temp.resolve("orphan-upload-data"));
        Path directory = store.createJobDirectory("orphan-job");
        Path orphan = directory.resolve("work").resolve("upload-test.part");
        Path unrelated = directory.resolve("work").resolve("keep.txt");
        Files.writeString(orphan, "partial");
        Files.writeString(unrelated, "keep");

        try (JobService ignored = new JobService(store, new ProcessorRegistry(), RuleEngine.createDefault())) {
            assertFalse(Files.exists(orphan));
            assertTrue(Files.exists(unrelated));
        }
    }

    @Test
    void reservesMoreWorkspaceForRasterizedPdfThanOffice() {
        JobRecord pdf = new JobRecord("pdf", "p", "large.pdf", temp.resolve("large.pdf"),
                Instant.now(), JobStatus.UPLOADED);
        pdf.uploaded("hash", 512L * 1024 * 1024);
        JobRecord office = new JobRecord("office", "p", "large.docx", temp.resolve("large.docx"),
                Instant.now(), JobStatus.UPLOADED);
        office.uploaded("hash", 512L * 1024 * 1024);

        assertEquals(3L * 1024 * 1024 * 1024, JobService.workspaceReservation(pdf));
        assertEquals(1536L * 1024 * 1024, JobService.workspaceReservation(office));
    }

    @Test
    void estimatesBatchCapacityByModeAndUsesPeakWorkspace() throws Exception {
        JobStore store = new JobStore(temp.resolve("capacity-data"));
        try (JobService service = new JobService(store, new ProcessorRegistry(), RuleEngine.createDefault())) {
            long officeSize = 512L * 1024 * 1024;
            long imageSize = 64L * 1024 * 1024;
            JobService.CapacityEstimate irreversible = service.estimateCapacity(List.of(
                    new JobService.CapacityFile("large.docx", officeSize),
                    new JobService.CapacityFile("scan.png", imageSize)), "external_irreversible");
            assertEquals(officeSize + imageSize, irreversible.selectedBytes());
            assertEquals(officeSize + imageSize, irreversible.persistentBytes());
            assertEquals(1536L * 1024 * 1024, irreversible.peakWorkspaceBytes());
            assertFalse(irreversible.archiveEstimateIncomplete());

            JobService.CapacityEstimate reversible = service.estimateCapacity(List.of(
                    new JobService.CapacityFile("large.docx", officeSize)), "reversible_vault");
            assertEquals(2L * officeSize, reversible.persistentBytes());

            JobService.CapacityEstimate archive = service.estimateCapacity(List.of(
                    new JobService.CapacityFile("bundle.zip", 100L * 1024 * 1024)), "external_irreversible");
            assertTrue(archive.archiveEstimateIncomplete());
            assertEquals(300L * 1024 * 1024, archive.peakWorkspaceBytes());
            assertThrows(IllegalArgumentException.class, () -> service.estimateCapacity(List.of(
                    new JobService.CapacityFile("oversized.pdf", JobService.MAX_UPLOAD_BYTES + 1)),
                    "external_irreversible"));
        }
    }

    @Test
    void validatesArchiveDecisionsBeforeProcessing() throws Exception {
        byte[] archiveBytes;
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             ZipArchiveOutputStream zip = new ZipArchiveOutputStream(buffer)) {
            addZipEntry(zip, "case.docx", syntheticDocx());
            addZipEntry(zip, "notes.txt", "ordinary attachment".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            addZipEntry(zip, "run.ps1", "Write-Host unsafe".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.finish();
            archiveBytes = buffer.toByteArray();
        }
        JobStore store = new JobStore(temp.resolve("archive-decision-data"));
        try (JobService service = new JobService(store, new ProcessorRegistry(), RuleEngine.createDefault())) {
            JobRecord job = service.accept("Archive decisions", "case.zip", new ByteArrayInputStream(archiveBytes),
                    "external_irreversible", null, false,
                    "77777777-7777-7777-7777-777777777777", archiveBytes.length);
            waitFor(job, JobStatus.AWAITING_CONFIRMATION);
            ArchiveInspection inspection = service.archiveInspection(job.id());
            int document = inspection.entries().stream().filter(entry -> entry.path().equals("case.docx"))
                    .findFirst().orElseThrow().index();
            int notes = inspection.entries().stream().filter(entry -> entry.path().equals("notes.txt"))
                    .findFirst().orElseThrow().index();
            int script = inspection.entries().stream().filter(entry -> entry.path().equals("run.ps1"))
                    .findFirst().orElseThrow().index();

            assertThrows(IllegalArgumentException.class, () -> service.confirmArchive(job.id(),
                    Map.of(document, ArchiveEntryAction.KEEP_UNPROCESSED),
                    ArchiveInspection.INCLUDE_UNPROCESSED_CONFIRMATION));
            assertThrows(IllegalArgumentException.class, () -> service.confirmArchive(job.id(),
                    Map.of(document, ArchiveEntryAction.EXCLUDE, notes, ArchiveEntryAction.KEEP_UNPROCESSED), ""));
            assertThrows(IllegalArgumentException.class, () -> service.confirmArchive(job.id(),
                    Map.of(script, ArchiveEntryAction.KEEP_UNPROCESSED),
                    ArchiveInspection.INCLUDE_UNPROCESSED_CONFIRMATION));

            service.confirmArchive(job.id(), Map.of(document, ArchiveEntryAction.EXCLUDE,
                            notes, ArchiveEntryAction.KEEP_UNPROCESSED, script, ArchiveEntryAction.EXCLUDE),
                    ArchiveInspection.INCLUDE_UNPROCESSED_CONFIRMATION);
            waitFor(job, JobStatus.COMPLETED);
            try (ZipFile zip = ZipFile.builder().setPath(job.outputPath()).get()) {
                assertEquals(null, zip.getEntry("case.docx"));
                assertTrue(zip.getEntry("notes.txt") != null);
                assertEquals(null, zip.getEntry("run.ps1"));
            }
        }
    }

    private static void addZipEntry(ZipArchiveOutputStream zip, String name, byte[] bytes) throws IOException {
        ZipArchiveEntry entry = new ZipArchiveEntry(name);
        entry.setSize(bytes.length);
        zip.putArchiveEntry(entry);
        zip.write(bytes);
        zip.closeArchiveEntry();
    }

    private static void waitFor(JobRecord job, JobStatus expected) throws InterruptedException {
        for (int attempt = 0; attempt < 100 && job.status() != expected && job.status() != JobStatus.FAILED; attempt++) {
            Thread.sleep(50);
        }
        assertEquals(expected, job.status(), job.toJson());
    }

    private static byte[] syntheticDocx() throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("手机号13800138000");
            document.write(output);
            return output.toByteArray();
        }
    }
}

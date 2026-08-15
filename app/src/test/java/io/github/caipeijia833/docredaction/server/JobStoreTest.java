/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.github.caipeijia833.docredaction.processor.ProcessReport;
import io.github.caipeijia833.docredaction.processor.WorkerTelemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobStoreTest {
    @TempDir
    Path temp;

    @Test
    void persistsWorkerResourceTelemetryInHistory() throws Exception {
        Path data = temp.resolve("telemetry-data");
        JobStore first = new JobStore(data);
        Path directory = first.createJobDirectory("telemetry-job");
        Path input = Files.write(directory.resolve("original").resolve("case.pdf"), new byte[] {1});
        Path output = Files.write(directory.resolve("output").resolve("case.pdf"), new byte[] {2});
        JobRecord record = new JobRecord("telemetry-job", "99999999-9999-9999-9999-999999999999",
                "Telemetry", "case.pdf", input, Instant.now(), JobStatus.UPLOADED);
        ProcessReport report = new ProcessReport();
        report.workerTelemetry(new WorkerTelemetry(true, true, 4_294_967_296L,
                700_000_000L, 600_000_000L, 1_000L, 9_000L, 8_000L, "applied"));
        record.completed(output, report);
        first.put(record);

        JobRecord reloaded = new JobStore(data).list().getFirst();
        assertTrue(reloaded.workerTelemetry().windowsJobObjectApplied());
        assertTrue(reloaded.workerTelemetry().isolationRequired());
        assertEquals(700_000_000L, reloaded.workerTelemetry().peakJobCommittedBytes());
        assertEquals(8_000L, reloaded.workerTelemetry().peakAdditionalDiskBytes());
        assertTrue(reloaded.toJson().contains("\"workerTelemetry\":"));
    }

    @Test
    void persistsTransactionalIndexAndReloadsHistory() throws Exception {
        Path data = temp.resolve("data");
        JobStore first = new JobStore(data);
        Path directory = first.createJobDirectory("job-1");
        Path input = directory.resolve("original").resolve("case.docx");
        Files.write(input, new byte[] {1, 2, 3});
        JobRecord record = new JobRecord("job-1", "11111111-1111-1111-1111-111111111111",
                "测试项目", "case.docx", input, Instant.parse("2026-08-11T00:00:00Z"), JobStatus.UPLOADED);
        record.uploaded("abc", 3);
        first.put(record);

        assertTrue(Files.isRegularFile(first.databasePath()));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + first.databasePath());
                var statement = connection.prepareStatement(
                        "SELECT project_id, status, size_bytes FROM jobs WHERE id = ?")) {
            statement.setString(1, "job-1");
            try (var results = statement.executeQuery()) {
                assertTrue(results.next());
                assertEquals("11111111-1111-1111-1111-111111111111", results.getString(1));
                assertEquals("UPLOADED", results.getString(2));
                assertEquals(3L, results.getLong(3));
            }
        }

        JobStore reopened = new JobStore(data);
        assertEquals(1, reopened.list().size());
        assertEquals("job-1", reopened.list().getFirst().id());
        assertEquals("11111111-1111-1111-1111-111111111111", reopened.list().getFirst().projectId());
    }

    @Test
    void recordsStatusTransitionsOnlyWhenStatusChanges() throws Exception {
        JobStore store = new JobStore(temp.resolve("audit-data"));
        Path directory = store.createJobDirectory("job-2");
        JobRecord record = new JobRecord("job-2", "22222222-2222-2222-2222-222222222222",
                "项目", "case.pdf", directory.resolve("original").resolve("case.pdf"),
                Instant.now(), JobStatus.UPLOADED);
        store.put(record);
        store.save(record);
        record.processing();
        store.save(record);

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + store.databasePath());
                var statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM audit_events WHERE job_id = ?")) {
            statement.setString(1, "job-2");
            try (var results = statement.executeQuery()) {
                assertTrue(results.next());
                assertEquals(2, results.getInt(1));
            }
        }
    }

    @Test
    void concurrentSaveNeverInvertsJobAndStoreLocks() throws Exception {
        JobStore store = new JobStore(temp.resolve("locking-data"));
        Path directory = store.createJobDirectory("job-lock");
        JobRecord record = new JobRecord("job-lock", "77777777-7777-7777-7777-777777777777",
                "并发项目", "case.pdf", directory.resolve("original").resolve("case.pdf"),
                Instant.now(), JobStatus.UPLOADED);
        store.put(record);
        CountDownLatch jobLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread first = Thread.startVirtualThread(() -> {
            synchronized (record) {
                jobLocked.countDown();
                try {
                    releaseFirst.await();
                    store.save(record);
                } catch (Throwable ex) {
                    failure.compareAndSet(null, ex);
                }
            }
        });
        assertTrue(jobLocked.await(2, TimeUnit.SECONDS));
        Thread second = Thread.startVirtualThread(() -> {
            try {
                store.save(record);
            } catch (Throwable ex) {
                failure.compareAndSet(null, ex);
            }
        });
        Thread.sleep(100);
        releaseFirst.countDown();
        first.join(3_000);
        second.join(3_000);

        assertTrue(!first.isAlive() && !second.isAlive(), "并发持久化发生锁等待或死锁");
        if (failure.get() != null) {
            throw new AssertionError("并发持久化失败", failure.get());
        }
    }

    @Test
    void skipsTamperedStateThatPointsOutsideItsJobDirectory() throws Exception {
        Path data = temp.resolve("tampered-data");
        Path directory = data.resolve("projects").resolve("tampered-job");
        Files.createDirectories(directory.resolve("original"));
        Files.createDirectories(directory.resolve("output"));
        Files.createDirectories(directory.resolve("work"));
        Files.createDirectories(directory.resolve("vault"));
        Path victim = temp.resolve("must-survive.txt");
        Files.writeString(victim, "do not delete");
        JobRecord record = new JobRecord("tampered-job", "88888888-8888-8888-8888-888888888888",
                "篡改状态", "case.docx", victim, Instant.now(), JobStatus.PROCESSING);
        Properties properties = record.toProperties();
        try (var writer = Files.newBufferedWriter(directory.resolve("job.properties"))) {
            properties.store(writer, null);
        }

        JobStore reopened = new JobStore(data);

        assertTrue(reopened.list().isEmpty());
        assertTrue(Files.isRegularFile(victim));
    }
}

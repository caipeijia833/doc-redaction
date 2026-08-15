/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerResourceMonitorTest {
    @TempDir
    Path temp;

    @Test
    void recordsPeakEvenAfterTemporaryFileIsRemoved() throws Exception {
        Path job = Files.createDirectory(temp.resolve("job"));
        Files.write(job.resolve("original.bin"), new byte[1024]);

        WorkerResourceMonitor.DiskSnapshot snapshot;
        try (WorkerResourceMonitor monitor = WorkerResourceMonitor.start(job)) {
            Path temporary = job.resolve("temporary.bin");
            Files.write(temporary, new byte[64 * 1024]);
            Thread.sleep(550L);
            Files.delete(temporary);
            snapshot = monitor.snapshot();
        }

        assertEquals(1024L, snapshot.baselineBytes());
        assertTrue(snapshot.peakBytes() >= 65 * 1024L);
        assertTrue(snapshot.additionalBytes() >= 64 * 1024L);
    }

    @Test
    void doesNotFollowSymbolicLinksOutsideJobDirectory() throws Exception {
        Path job = Files.createDirectory(temp.resolve("linked-job"));
        Files.write(job.resolve("inside.bin"), new byte[123]);
        Path outside = Files.write(temp.resolve("outside.bin"), new byte[8192]);
        try {
            Files.createSymbolicLink(job.resolve("outside-link.bin"), outside);
        } catch (IOException | UnsupportedOperationException | SecurityException ex) {
            Assumptions.abort("Symbolic links are unavailable in this test environment");
        }

        assertEquals(123L, WorkerResourceMonitor.directoryBytes(job));
    }
}

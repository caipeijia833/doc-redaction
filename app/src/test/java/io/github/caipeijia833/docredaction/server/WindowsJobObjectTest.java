/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.jna.Native;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class WindowsJobObjectTest {
    @Test
    void structureMatchesDocumentedX64Layout() {
        Assumptions.assumeTrue(Native.POINTER_SIZE == 8);
        assertEquals(64, new WindowsJobObject.JobObjectBasicLimitInformation().size());
        assertEquals(48, new WindowsJobObject.IoCounters().size());
        assertEquals(144, new WindowsJobObject.JobObjectExtendedLimitInformation().size());
    }

    @Test
    void attachesQueriesAndKillsWorkerOnClose() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("win"));
        String executable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        Process process = new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"),
                Sleeper.class.getName()).start();
        WindowsJobObject job = null;
        try {
            job = WindowsJobObject.attach(process, 1024L * 1024 * 1024, true);
            WindowsJobObject.JobSnapshot snapshot = job.snapshot();
            assertTrue(snapshot.applied());
            assertTrue(snapshot.required());
            assertEquals(1024L * 1024 * 1024, snapshot.limitBytes());
            assertTrue(snapshot.peakJobCommittedBytes() > 0L);
        } finally {
            if (job != null) {
                job.close();
            } else {
                process.destroyForcibly();
            }
        }
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        assertFalse(process.isAlive());
    }

    public static final class Sleeper {
        private Sleeper() {
        }

        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(60_000L);
        }
    }
}

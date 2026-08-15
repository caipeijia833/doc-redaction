/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Samples the real files in one job directory without following links. */
final class WorkerResourceMonitor implements AutoCloseable {
    private static final long SAMPLE_INTERVAL_MILLIS = 250L;

    private final Path jobDirectory;
    private final long baselineBytes;
    private final AtomicLong peakBytes;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread sampler;

    private WorkerResourceMonitor(Path jobDirectory) {
        this.jobDirectory = jobDirectory;
        this.baselineBytes = directoryBytes(jobDirectory);
        this.peakBytes = new AtomicLong(baselineBytes);
        this.sampler = Thread.startVirtualThread(this::sampleUntilClosed);
    }

    static WorkerResourceMonitor start(Path jobDirectory) throws IOException {
        Path normalized = jobDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(normalized)) {
            throw new IOException("Worker telemetry root must be a real job directory");
        }
        return new WorkerResourceMonitor(normalized);
    }

    DiskSnapshot snapshot() {
        sampleOnce();
        long peak = peakBytes.get();
        return new DiskSnapshot(baselineBytes, peak, Math.max(0L, peak - baselineBytes));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        sampleOnce();
        sampler.interrupt();
        try {
            sampler.join(2_000L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void sampleUntilClosed() {
        while (!closed.get()) {
            sampleOnce();
            try {
                Thread.sleep(SAMPLE_INTERVAL_MILLIS);
            } catch (InterruptedException ex) {
                if (closed.get()) {
                    return;
                }
            }
        }
    }

    private void sampleOnce() {
        peakBytes.accumulateAndGet(directoryBytes(jobDirectory), Math::max);
    }

    static long directoryBytes(Path root) {
        if (root == null || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root)) {
            return 0L;
        }
        long total = 0L;
        try (var paths = Files.walk(root)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                try {
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        long size = Files.size(path);
                        total = size > Long.MAX_VALUE - total ? Long.MAX_VALUE : total + size;
                    }
                } catch (IOException | SecurityException ignored) {
                    // Concurrent temporary-file deletion is expected; the next sample catches it.
                }
            }
        } catch (IOException | SecurityException ignored) {
            // Preserve the last trustworthy sample when a concurrent directory change races the walk.
        }
        return total;
    }

    record DiskSnapshot(long baselineBytes, long peakBytes, long additionalBytes) {
    }
}

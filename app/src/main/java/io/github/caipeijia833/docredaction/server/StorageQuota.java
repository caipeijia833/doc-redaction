/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.server;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/** Coordinates disk reservations for concurrent local jobs. */
public final class StorageQuota {
    public static final long DEFAULT_MAX_DATA_BYTES = 20L * 1024 * 1024 * 1024;
    public static final long DEFAULT_MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024;

    private final JobStore store;
    private final long maxDataBytes;
    private final long minFreeBytes;
    private final Map<String, Long> reservations = new HashMap<>();

    public StorageQuota(JobStore store) {
        this(store,
                positiveLongProperty("docredaction.data.maxBytes", DEFAULT_MAX_DATA_BYTES),
                positiveLongProperty("docredaction.data.minFreeBytes", DEFAULT_MIN_FREE_BYTES));
    }

    StorageQuota(JobStore store, long maxDataBytes, long minFreeBytes) {
        if (maxDataBytes <= 0 || minFreeBytes < 0) {
            throw new IllegalArgumentException("磁盘配额参数无效");
        }
        this.store = store;
        this.maxDataBytes = maxDataBytes;
        this.minFreeBytes = minFreeBytes;
    }

    public synchronized Reservation reserve(String owner, long requestedBytes) throws IOException {
        if (owner == null || owner.isBlank() || requestedBytes < 0) {
            throw new IllegalArgumentException("磁盘预留参数无效");
        }
        if (reservations.containsKey(owner)) {
            throw new IllegalStateException("任务已持有磁盘预留");
        }
        long reserved = 0L;
        for (long value : reservations.values()) {
            try {
                reserved = Math.addExact(reserved, value);
            } catch (ArithmeticException ex) {
                throw new IOException("磁盘预留总量溢出，已拒绝新任务", ex);
            }
        }
        long usage = store.dataUsageBytes();
        if (exceeds(usage, reserved, requestedBytes, maxDataBytes)) {
            throw new IOException("本地数据目录配额不足，请先删除历史任务或调高配额");
        }
        FileStore fileStore = Files.getFileStore(store.dataRoot());
        long usable = fileStore.getUsableSpace();
        if (exceeds(minFreeBytes, reserved, requestedBytes, usable)) {
            throw new IOException("磁盘剩余空间不足，已停止接收或处理新任务");
        }
        reservations.put(owner, requestedBytes);
        return new Reservation(owner);
    }

    public long maxDataBytes() {
        return maxDataBytes;
    }

    public long minFreeBytes() {
        return minFreeBytes;
    }

    public long currentDataBytes() throws IOException {
        return store.dataUsageBytes();
    }

    public long usableDiskBytes() throws IOException {
        return Files.getFileStore(store.dataRoot()).getUsableSpace();
    }

    private synchronized void release(String owner) {
        reservations.remove(owner);
    }

    private static boolean exceeds(long first, long second, long third, long limit) {
        try {
            return Math.addExact(Math.addExact(first, second), third) > limit;
        } catch (ArithmeticException ex) {
            return true;
        }
    }

    private static long positiveLongProperty(String name, long fallback) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public final class Reservation implements AutoCloseable {
        private final String owner;
        private boolean closed;

        private Reservation(String owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                release(owner);
            }
        }
    }
}

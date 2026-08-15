/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Prevents simultaneous service instances and gives the offline updater a hard stop check. */
public final class InstanceLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private InstanceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static InstanceLock acquire(Path dataRoot) throws IOException {
        Path root = dataRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Application data root must be a real directory");
        }
        Path lockFile = root.resolve("runtime.lock").normalize();
        if (!lockFile.startsWith(root) || Files.isSymbolicLink(lockFile)) {
            throw new IOException("Unsafe application instance lock path");
        }
        FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException ex) {
                lock = null;
            }
            if (lock == null) {
                throw new IOException("Another document redaction service instance is still running");
            }
            channel.truncate(0L);
            channel.position(0L);
            byte[] marker = ("pid=" + ProcessHandle.current().pid() + "\n").getBytes(StandardCharsets.US_ASCII);
            channel.write(ByteBuffer.wrap(marker));
            channel.force(true);
            return new InstanceLock(channel, lock);
        } catch (IOException | RuntimeException ex) {
            channel.close();
            throw ex;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } finally {
            channel.close();
        }
    }
}

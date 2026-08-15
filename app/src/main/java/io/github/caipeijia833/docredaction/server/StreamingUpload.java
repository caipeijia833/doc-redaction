/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Transactional, bounded upload writer used for large local files. */
final class StreamingUpload {
    private static final int BUFFER_BYTES = 1024 * 1024;

    private StreamingUpload() {
    }

    static UploadResult copy(InputStream input, Path temporaryTarget, long limit, long declaredLength)
            throws IOException {
        if (input == null || temporaryTarget == null || limit <= 0 || declaredLength < -1L) {
            throw new IllegalArgumentException("上传流参数无效");
        }
        MessageDigest digest = sha256Digest();
        long total = 0L;
        byte[] bytes = new byte[BUFFER_BYTES];
        try (FileChannel output = FileChannel.open(temporaryTarget,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int read;
            while ((read = input.read(bytes)) >= 0) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("文件上传已取消");
                }
                if (read == 0) {
                    continue;
                }
                if (read > limit - total) {
                    throw new IOException("文件超过1GiB上限");
                }
                digest.update(bytes, 0, read);
                ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, read);
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                total += read;
            }
            if (declaredLength >= 0L && total != declaredLength) {
                throw new IOException("上传正文长度与Content-Length不一致");
            }
            output.force(true);
        } catch (IOException | RuntimeException ex) {
            Files.deleteIfExists(temporaryTarget);
            throw ex;
        }
        return new UploadResult(total, HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

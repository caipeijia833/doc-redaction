/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StreamingUploadTest {
    @TempDir
    Path temp;

    @Test
    void acceptsExactBoundaryWithoutBufferingWholeBody() throws Exception {
        long limit = 8L * 1024 * 1024;
        Path target = temp.resolve("exact.part");
        UploadResult result = StreamingUpload.copy(new RepeatingInputStream(limit), target, limit, limit);

        assertEquals(limit, result.size());
        assertEquals(limit, Files.size(target));
        assertEquals(hashOfZeros(limit), result.sha256());
    }

    @Test
    void rejectsOneByteOverLimitAndDeletesPartialFile() {
        long limit = 2L * 1024 * 1024;
        Path target = temp.resolve("oversize.part");
        IOException error = assertThrows(IOException.class,
                () -> StreamingUpload.copy(new RepeatingInputStream(limit + 1), target, limit, -1L));
        assertEquals("文件超过1GiB上限", error.getMessage());
        assertFalse(Files.exists(target));
    }

    @Test
    void rejectsTruncatedDeclaredBodyAndDeletesPartialFile() {
        Path target = temp.resolve("truncated.part");
        IOException error = assertThrows(IOException.class, () -> StreamingUpload.copy(
                new ByteArrayInputStream(new byte[1024]), target, 4096L, 2048L));
        assertEquals("上传正文长度与Content-Length不一致", error.getMessage());
        assertFalse(Files.exists(target));
    }

    private static String hashOfZeros(long length) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1024 * 1024];
        long remaining = length;
        while (remaining > 0) {
            int count = (int) Math.min(buffer.length, remaining);
            digest.update(buffer, 0, count);
            remaining -= count;
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static final class RepeatingInputStream extends InputStream {
        private long remaining;

        private RepeatingInputStream(long remaining) {
            this.remaining = remaining;
        }

        @Override
        public int read() {
            if (remaining == 0) {
                return -1;
            }
            remaining--;
            return 0;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (remaining == 0) {
                return -1;
            }
            int count = (int) Math.min(length, remaining);
            java.util.Arrays.fill(buffer, offset, offset + count, (byte) 0);
            remaining -= count;
            return count;
        }
    }
}

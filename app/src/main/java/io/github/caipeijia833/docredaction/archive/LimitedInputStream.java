/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.archive;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

final class LimitedInputStream extends FilterInputStream {
    private final long maximum;
    private long count;

    LimitedInputStream(InputStream input, long maximum) {
        super(input);
        this.maximum = maximum;
    }

    @Override
    public int read() throws IOException {
        ensureRemaining();
        int value = super.read();
        if (value >= 0) {
            count++;
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        ensureRemaining();
        int allowed = (int) Math.min(length, maximum - count);
        int read = super.read(buffer, offset, allowed);
        if (read > 0) {
            count += read;
        }
        return read;
    }

    @Override
    public long skip(long requested) throws IOException {
        ensureRemaining();
        long skipped = super.skip(Math.min(requested, maximum - count));
        count += skipped;
        return skipped;
    }

    private void ensureRemaining() throws IOException {
        if (count >= maximum) {
            throw new IOException("压缩包实际展开数据超过8GB安全上限");
        }
    }
}

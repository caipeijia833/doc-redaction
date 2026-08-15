/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.archive;

import java.io.IOException;

/** Counts bytes actually read from selected archive entries across the whole job. */
final class ExpandedDataLimiter {
    private final long maximum;
    private long consumed;

    ExpandedDataLimiter(long maximum) {
        if (maximum < 0) {
            throw new IllegalArgumentException("展开量上限不能为负数");
        }
        this.maximum = maximum;
    }

    void record(long bytes) throws LimitExceededException {
        if (bytes < 0 || consumed > maximum - bytes) {
            throw new LimitExceededException("压缩包实际展开总量超过8GB安全上限");
        }
        consumed += bytes;
    }

    long consumed() {
        return consumed;
    }

    static final class LimitExceededException extends IOException {
        LimitExceededException(String message) {
            super(message);
        }
    }
}

/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.archive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExpandedDataLimiterTest {
    @Test
    void appliesOneCumulativeLimitAcrossEntries() throws Exception {
        ExpandedDataLimiter limiter = new ExpandedDataLimiter(10);
        limiter.record(6);
        limiter.record(4);
        assertEquals(10, limiter.consumed());
        assertThrows(ExpandedDataLimiter.LimitExceededException.class, () -> limiter.record(1));
    }

    @Test
    void rejectsCounterOverflow() throws Exception {
        ExpandedDataLimiter limiter = new ExpandedDataLimiter(Long.MAX_VALUE);
        limiter.record(Long.MAX_VALUE);
        assertThrows(ExpandedDataLimiter.LimitExceededException.class, () -> limiter.record(1));
    }
}

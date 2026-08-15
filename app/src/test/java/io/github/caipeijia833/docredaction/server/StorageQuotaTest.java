/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.server;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageQuotaTest {
    @TempDir
    Path temp;

    @Test
    void rejectsReservationBeyondConfiguredDataLimit() throws Exception {
        JobStore store = new JobStore(temp.resolve("data"));
        long current = store.dataUsageBytes();
        StorageQuota quota = new StorageQuota(store, current + 128, 0);

        IOException error = assertThrows(IOException.class, () -> quota.reserve("too-large", 256));

        assertTrue(error.getMessage().contains("配额不足"));
    }
}

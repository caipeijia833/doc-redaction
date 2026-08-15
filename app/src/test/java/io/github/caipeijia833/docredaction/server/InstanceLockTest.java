/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.server;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceLockTest {
    @TempDir
    Path temp;

    @Test
    void blocksSecondInstanceAndReleasesCleanly() throws Exception {
        Path data = temp.resolve("data");
        try (InstanceLock first = InstanceLock.acquire(data)) {
            assertThrows(IOException.class, () -> InstanceLock.acquire(data));
        }
        try (InstanceLock ignored = InstanceLock.acquire(data)) {
            // A clean shutdown makes the package eligible for update or restart.
        }
    }
}

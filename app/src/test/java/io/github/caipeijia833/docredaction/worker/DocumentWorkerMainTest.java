/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.worker;

import org.apache.poi.util.IOUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class DocumentWorkerMainTest {
    @Test
    void parserLimitConfigurationIsExplicitAndBounded() {
        assertDoesNotThrow(DocumentWorkerMain::configureParserLimits);
        IOUtils.setByteArrayMaxOverride(-1);
    }
}

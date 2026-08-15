/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LocalVlmEngineTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void parsesOnlyBoundedKnownRegions() throws Exception {
        var root = JSON.readTree("""
                {"findings":[
                  {"type":"bank_card","bbox":[100,200,500,400],"confidence":0.91,"label":"card"},
                  {"type":"unknown","bbox":[0,0,10,10]},
                  {"type":"face","bbox":[0,0,1000,1000]}
                ]}
                """);

        var detections = LocalVlmEngine.parseDetections(root, 1000, 500);

        assertEquals(1, detections.size());
        assertEquals("bank_card", detections.getFirst().type());
        assertEquals(100, detections.getFirst().x());
        assertEquals(100, detections.getFirst().y());
        assertEquals(400, detections.getFirst().width());
        assertEquals(100, detections.getFirst().height());
    }

    @Test
    void disabledModeNeverRequiresLocalFiles() {
        String previous = System.getProperty("docredaction.vlm.mode");
        try {
            System.setProperty("docredaction.vlm.mode", "disabled");
            var capability = LocalVlmEngine.inspect();
            assertFalse(capability.available());
            assertEquals("disabled", capability.mode());
        } finally {
            if (previous == null) System.clearProperty("docredaction.vlm.mode");
            else System.setProperty("docredaction.vlm.mode", previous);
        }
    }

    @Test
    void autoModeReportsMissingPinnedComponents() {
        String previousMode = System.getProperty("docredaction.vlm.mode");
        String previousExecutable = System.getProperty("docredaction.vlm.executable");
        try {
            System.setProperty("docredaction.vlm.mode", "auto");
            System.setProperty("docredaction.vlm.executable", Path.of("missing-vlm-runtime").toString());
            var capability = LocalVlmEngine.inspect();
            assertFalse(capability.available());
            assertEquals("auto", capability.mode());
        } finally {
            restore("docredaction.vlm.mode", previousMode);
            restore("docredaction.vlm.executable", previousExecutable);
        }
    }

    private static void restore(String name, String value) {
        if (value == null) System.clearProperty(name); else System.setProperty(name, value);
    }
}

/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporalVisualTrackerTest {
    @Test
    void bridgesCompatibleFastMotionBetweenAdjacentSamples() {
        MediaFinding first = visual(0, 900, 1_100, 10, 10, 30, 30);
        MediaFinding second = visual(0, 1_900, 2_100, 100, 20, 30, 30);

        TemporalVisualTracker.Result result = TemporalVisualTracker.bridge(List.of(first, second), 1.0d);

        assertEquals(1, result.bridgeCount());
        MediaFinding bridge = result.findings().stream()
                .filter(finding -> finding.startMillis() == 1_000L && finding.endMillis() == 2_000L)
                .findFirst().orElseThrow();
        assertTrue(bridge.x() <= first.x());
        assertTrue(bridge.x() + bridge.width() >= second.x() + second.width());
    }

    @Test
    void doesNotBridgeImplausiblyDistantOrDifferentStreams() {
        MediaFinding first = visual(0, 900, 1_100, 10, 10, 20, 20);
        MediaFinding far = visual(0, 1_900, 2_100, 500, 400, 20, 20);
        MediaFinding otherStream = visual(1, 1_900, 2_100, 20, 20, 20, 20);

        TemporalVisualTracker.Result result = TemporalVisualTracker.bridge(
                List.of(first, far, otherStream), 1.0d);

        assertEquals(0, result.bridgeCount());
        assertEquals(3, result.findings().size());
    }

    @Test
    void doesNotJoinSameFrameDetectionsIntoOneTrack() {
        MediaFinding first = visual(0, 900, 1_100, 10, 10, 30, 30);
        MediaFinding second = visual(0, 900, 1_100, 40, 10, 30, 30);

        TemporalVisualTracker.Result result = TemporalVisualTracker.bridge(List.of(first, second), 1.0d);

        assertEquals(0, result.bridgeCount());
    }

    private static MediaFinding visual(int stream, long start, long end,
            int x, int y, int width, int height) {
        return new MediaFinding(MediaFinding.Kind.FACE, stream, "VISUAL_FACE", "人脸", "", "",
                start, end, x, y, width, height, 0.9d);
    }
}

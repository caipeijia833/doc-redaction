/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import java.util.Locale;

/** A time-bounded audio finding or a time-and-coordinate-bounded visual finding. */
record MediaFinding(
        Kind kind,
        int streamIndex,
        String ruleId,
        String label,
        String value,
        String context,
        long startMillis,
        long endMillis,
        int x,
        int y,
        int width,
        int height,
        double confidence) {

    enum Kind {
        AUDIO,
        SUBTITLE,
        OCR_TEXT,
        FACE,
        LICENSE_PLATE,
        QR_CODE
    }

    MediaFinding {
        if (kind == null || streamIndex < 0 || ruleId == null || label == null
                || startMillis < 0 || endMillis <= startMillis) {
            throw new IllegalArgumentException("音视频命中项参数无效");
        }
        value = value == null ? "" : value;
        context = context == null ? "" : context;
        confidence = Math.max(0.0d, Math.min(1.0d, confidence));
    }

    boolean visual() {
        return kind == Kind.OCR_TEXT || kind == Kind.FACE
                || kind == Kind.LICENSE_PLATE || kind == Kind.QR_CODE;
    }

    String ignoreKey() {
        return String.format(Locale.ROOT, "media:%s:%d:%d:%d:%d:%d:%d:%d",
                kind.name(), streamIndex, startMillis / 100L, endMillis / 100L,
                x / 4, y / 4, width / 4, height / 4);
    }

    boolean overlapsInTime(MediaFinding other, long maximumGapMillis) {
        return streamIndex == other.streamIndex && kind == other.kind
                && other.startMillis <= endMillis + maximumGapMillis
                && startMillis <= other.endMillis + maximumGapMillis;
    }

    double intersectionOverUnion(MediaFinding other) {
        if (!visual() || !other.visual()) {
            return 0.0d;
        }
        int left = Math.max(x, other.x);
        int top = Math.max(y, other.y);
        int right = Math.min(x + width, other.x + other.width);
        int bottom = Math.min(y + height, other.y + other.height);
        long intersection = Math.max(0, right - left) * (long) Math.max(0, bottom - top);
        long union = width * (long) height + other.width * (long) other.height - intersection;
        return union <= 0L ? 0.0d : intersection / (double) union;
    }

    MediaFinding merge(MediaFinding other) {
        int left = Math.min(x, other.x);
        int top = Math.min(y, other.y);
        int right = Math.max(x + width, other.x + other.width);
        int bottom = Math.max(y + height, other.y + other.height);
        String mergedValue = value.isBlank() ? other.value : value;
        String mergedContext = context.isBlank() ? other.context : context;
        return new MediaFinding(kind, streamIndex, ruleId, label, mergedValue, mergedContext,
                Math.min(startMillis, other.startMillis), Math.max(endMillis, other.endMillis),
                left, top, right - left, bottom - top, Math.max(confidence, other.confidence));
    }
}

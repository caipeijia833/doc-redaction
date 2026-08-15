/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.review;

import io.github.caipeijia833.docredaction.util.JsonUtil;

public record ReviewItem(
        int id,
        String ruleId,
        String label,
        String location,
        String value,
        String context,
        String mediaKind,
        int streamIndex,
        long startMillis,
        long endMillis,
        int x,
        int y,
        int width,
        int height,
        double confidence,
        String ignoreKey) {

    public ReviewItem(int id, String ruleId, String label, String location,
            String value, String context) {
        this(id, ruleId, label, location, value, context,
                "", -1, -1L, -1L, -1, -1, -1, -1, 0.0d, value);
    }

    public ReviewItem {
        mediaKind = mediaKind == null ? "" : mediaKind;
        ignoreKey = ignoreKey == null || ignoreKey.isBlank() ? value : ignoreKey;
    }

    public String toJson() {
        return "{" +
                "\"id\":" + id + ',' +
                "\"ruleId\":" + JsonUtil.quote(ruleId) + ',' +
                "\"label\":" + JsonUtil.quote(label) + ',' +
                "\"location\":" + JsonUtil.quote(location) + ',' +
                "\"value\":" + JsonUtil.quote(value) + ',' +
                "\"context\":" + JsonUtil.quote(context) + ',' +
                "\"mediaKind\":" + JsonUtil.quote(mediaKind) + ',' +
                "\"streamIndex\":" + streamIndex + ',' +
                "\"startMillis\":" + startMillis + ',' +
                "\"endMillis\":" + endMillis + ',' +
                "\"x\":" + x + ',' +
                "\"y\":" + y + ',' +
                "\"width\":" + width + ',' +
                "\"height\":" + height + ',' +
                "\"confidence\":" + confidence + ',' +
                "\"ignoreKey\":" + JsonUtil.quote(ignoreKey) +
                "}";
    }
}

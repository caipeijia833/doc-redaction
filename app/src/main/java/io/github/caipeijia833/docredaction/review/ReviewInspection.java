/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.review;

import io.github.caipeijia833.docredaction.util.JsonUtil;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

public record ReviewInspection(List<ReviewItem> items, List<String> warnings, boolean truncated) {
    public ReviewInspection {
        items = List.copyOf(items);
        warnings = List.copyOf(warnings);
    }

    public String toJson() {
        Map<String, Integer> counts = new TreeMap<>();
        items.forEach(item -> counts.merge(item.ruleId(), 1, Integer::sum));
        String countJson = counts.entrySet().stream()
                .map(entry -> JsonUtil.quote(entry.getKey()) + ":" + entry.getValue())
                .collect(Collectors.joining(",", "{", "}"));
        String itemJson = items.stream().map(ReviewItem::toJson)
                .collect(Collectors.joining(",", "[", "]"));
        return "{" +
                "\"itemCount\":" + items.size() + ',' +
                "\"truncated\":" + truncated + ',' +
                "\"counts\":" + countJson + ',' +
                "\"warnings\":" + JsonUtil.stringArray(warnings) + ',' +
                "\"items\":" + itemJson +
                "}";
    }
}

/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.rules;

public record SensitiveMatch(
        String ruleId,
        String label,
        String category,
        int priority,
        int start,
        int end) {

    public int length() {
        return end - start;
    }
}

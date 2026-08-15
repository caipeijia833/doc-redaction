/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.rules;

public record RuleDefinition(
        String id,
        String label,
        String category,
        int priority,
        RedactionPattern pattern,
        int captureGroup,
        RuleValidator validator) {
}

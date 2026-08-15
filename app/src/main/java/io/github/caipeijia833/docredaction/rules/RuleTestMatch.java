/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.rules;

/** A bounded preview match produced before a custom rule is saved. */
public record RuleTestMatch(int start, int end, String value, String context) {
}

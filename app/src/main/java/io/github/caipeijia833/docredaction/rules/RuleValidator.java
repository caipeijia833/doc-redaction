/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.rules;

@FunctionalInterface
public interface RuleValidator {
    boolean isValid(String value, String fullText, int start, int end);
}

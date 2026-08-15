/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.rules;

/** Serializable user-defined rule description used by import/export and editing APIs. */
public record CustomRuleSpec(String id, String label, String category, int priority, String regex) {
}

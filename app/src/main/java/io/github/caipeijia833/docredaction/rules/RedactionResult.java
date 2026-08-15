/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.rules;

import java.util.List;
import java.util.Map;

public record RedactionResult(
        String redactedText,
        List<SensitiveMatch> matches,
        Map<String, Integer> counts) {
}

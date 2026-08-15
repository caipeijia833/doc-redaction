/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import java.util.List;

record MediaAnalysis(MediaProbe.Result probe, List<MediaFinding> findings, List<String> warnings) {
    MediaAnalysis {
        findings = List.copyOf(findings);
        warnings = List.copyOf(warnings);
    }
}

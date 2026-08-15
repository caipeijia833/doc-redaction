/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import io.github.caipeijia833.docredaction.rules.RuleEngine;

import java.nio.file.Path;
import java.util.Set;

public interface DocumentProcessor {
    Set<String> extensions();

    ProcessReport process(Path input, Path output, RuleEngine ruleEngine) throws Exception;
}

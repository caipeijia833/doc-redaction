/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleGoldCorpusTest {
    private static final double MIN_PRECISION = 0.99d;
    private static final double MIN_RECALL = 0.995d;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mainlandSyntheticGoldMeetsReleaseThresholds() throws Exception {
        RuleEngine engine = RuleEngine.createDefault();
        List<Map<String, Object>> failures = new ArrayList<>();
        int cases = 0;
        int expectedCount = 0;
        int truePositive = 0;
        int falsePositive = 0;
        int falseNegative = 0;

        var resource = RuleGoldCorpusTest.class.getResourceAsStream("/gold/cn-mainland-rules-v1.jsonl");
        assertNotNull(resource, "gold corpus resource");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isBlank()) continue;
                cases++;
                JsonNode testCase = mapper.readTree(line);
                String id = testCase.path("id").asText();
                String text = testCase.path("text").asText();
                Set<String> expected = new LinkedHashSet<>();
                testCase.path("expected").forEach(item -> expected.add(
                        item.path("ruleId").asText() + "\u0000" + item.path("value").asText()));
                Set<String> actual = new LinkedHashSet<>();
                for (SensitiveMatch match : engine.detect(text)) {
                    actual.add(match.ruleId() + "\u0000" + text.substring(match.start(), match.end()));
                }

                Set<String> correct = new LinkedHashSet<>(actual);
                correct.retainAll(expected);
                Set<String> extra = new LinkedHashSet<>(actual);
                extra.removeAll(expected);
                Set<String> missed = new LinkedHashSet<>(expected);
                missed.removeAll(actual);
                truePositive += correct.size();
                falsePositive += extra.size();
                falseNegative += missed.size();
                expectedCount += expected.size();
                if (!extra.isEmpty() || !missed.isEmpty()) {
                    failures.add(Map.of("id", id, "extra", extra, "missed", missed));
                }
            }
        }

        double precision = ratio(truePositive, truePositive + falsePositive);
        double recall = ratio(truePositive, truePositive + falseNegative);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("corpus", "cn-mainland-rules-v1");
        report.put("caseCount", cases);
        report.put("expectedMatchCount", expectedCount);
        report.put("truePositive", truePositive);
        report.put("falsePositive", falsePositive);
        report.put("falseNegative", falseNegative);
        report.put("precision", precision);
        report.put("recall", recall);
        report.put("minimumPrecision", MIN_PRECISION);
        report.put("minimumRecall", MIN_RECALL);
        report.put("failures", failures);
        report.put("status", precision >= MIN_PRECISION && recall >= MIN_RECALL ? "PASS" : "FAIL");
        Path output = Path.of("build", "reports", "gold", "cn-mainland-rules-v1.json");
        Files.createDirectories(output.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);

        assertTrue(precision >= MIN_PRECISION,
                () -> "precision=" + precision + ", failures=" + failures);
        assertTrue(recall >= MIN_RECALL,
                () -> "recall=" + recall + ", failures=" + failures);
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 1d : (double) numerator / denominator;
    }
}

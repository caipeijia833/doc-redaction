/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.rules;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ChineseAdministrativeDivisionRuleTest {
    private final RuleEngine engine = RuleEngine.createDefault();

    @Test
    void loadsThePinnedFourLevelOfflineLexicon() {
        ChineseAdministrativeDivisionLexicon lexicon = ChineseAdministrativeDivisionLexicon.instance();
        assertTrue(lexicon.termCount() > 35_000, "unexpectedly small lexicon");
        assertTrue(lexicon.isKnownCode("44"));
        assertTrue(lexicon.isKnownCode("4401"));
        assertTrue(lexicon.isKnownCode("440106"));
        assertTrue(lexicon.isKnownCode("440106014"));
        assertFalse(lexicon.isKnownCode("999999"));
    }

    @Test
    void detectsProvincePrefectureCountyTownshipAndRoadAddress() {
        String text = String.join("\n",
                "省份：广东省",
                "城市：广州市",
                "区县：天河区",
                "乡镇街道：石牌街道",
                "门牌：建国路88号",
                "行政区划代码：440106");
        Set<String> ids = engine.detect(text).stream().map(SensitiveMatch::ruleId)
                .collect(Collectors.toSet());
        assertTrue(ids.contains("CN_ADMIN_PROVINCE"), ids.toString());
        assertTrue(ids.contains("CN_ADMIN_PREFECTURE"), ids.toString());
        assertTrue(ids.contains("CN_ADMIN_COUNTY"), ids.toString());
        assertTrue(ids.contains("CN_ADMIN_TOWNSHIP"), ids.toString());
        assertTrue(ids.contains("CN_ROAD_ADDRESS"), ids.toString());
        assertTrue(ids.contains("CN_ADMIN_DIVISION_CODE_CONTEXT"), ids.toString());
    }

    @Test
    void allowsProvinceAliasesAndHierarchicalDisambiguation() {
        assertDetected("CN_ADMIN_PROVINCE", "项目所在地为广东");
        assertNotDetected("CN_ADMIN_COUNTY", "长安区是文稿中的孤立短语");
        Set<String> ids = engine.detect("行政区划：河北省石家庄市长安区育才街道").stream()
                .map(SensitiveMatch::ruleId).collect(Collectors.toSet());
        assertTrue(ids.contains("CN_ADMIN_COUNTY"), ids.toString());
        assertTrue(ids.contains("CN_ADMIN_TOWNSHIP"), ids.toString());
    }

    @Test
    void requiresContextOrHierarchyForTownshipNames() {
        assertNotDetected("CN_ADMIN_TOWNSHIP", "报告讨论东华门街道景观设计原则");
        assertDetected("CN_ADMIN_TOWNSHIP", "行政区划：北京市东城区东华门街道");
    }

    @Test
    void keepsLargeNoMatchLexiconScanLinearEnoughForLocalUse() {
        String text = "完全不包含行政区划名称的合成段落。".repeat(60_000);
        ChineseAdministrativeDivisionLexicon lexicon = ChineseAdministrativeDivisionLexicon.instance();
        assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> lexicon.find(text, ignored -> true, ignored -> false));
    }

    private void assertDetected(String ruleId, String text) {
        assertTrue(engine.detect(text).stream().anyMatch(match -> match.ruleId().equals(ruleId)),
                () -> ruleId + " not detected in: " + text);
    }

    private void assertNotDetected(String ruleId, String text) {
        assertFalse(engine.detect(text).stream().anyMatch(match -> match.ruleId().equals(ruleId)),
                () -> ruleId + " unexpectedly detected in: " + text);
    }
}

/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class RuleEngineTest {
    private final RuleEngine engine = RuleEngine.createDefault();

    @TempDir
    Path temp;

    @Test
    void detectsValidatedChineseIdentifiersAndContactData() {
        String id = validResidentId("11010519900101001");
        String creditCode = validCreditCode("91350000M000100Y4");
        String bankCard = appendLuhnCheckDigit("999999999999999");
        String text = "姓名：测试甲，身份证号" + id + "，统一社会信用代码" + creditCode
                + "，银行卡" + bankCard + "，手机13800138000，邮箱test@example.com。";

        RedactionResult result = engine.redact(text);
        Set<String> ids = result.matches().stream().map(SensitiveMatch::ruleId).collect(Collectors.toSet());

        assertTrue(ids.contains("CN_RESIDENT_ID"));
        assertTrue(ids.contains("CN_UNIFIED_SOCIAL_CREDIT_CODE"));
        assertTrue(ids.contains("BANK_CARD_NUMBER"));
        assertTrue(ids.contains("CN_MOBILE_PHONE"));
        assertTrue(ids.contains("EMAIL_ADDRESS"));
        assertFalse(result.redactedText().contains(id));
        assertEquals(text.length(), result.redactedText().length());
    }

    @Test
    void rejectsInvalidChecksumsAndInvalidIpv4() {
        String validId = validResidentId("11010519900101001");
        String invalidId = validId.substring(0, 17) + (validId.endsWith("0") ? "1" : "0");
        String validCreditCode = validCreditCode("91350000M000100Y4");
        String invalidCreditCode = validCreditCode.substring(0, 17)
                + (validCreditCode.endsWith("0") ? "1" : "0");
        String text = "身份证" + invalidId + "，信用代码" + invalidCreditCode + "，IP 999.2.3.4";
        assertTrue(engine.detect(text).stream().noneMatch(match ->
                match.ruleId().equals("CN_RESIDENT_ID")
                        || match.ruleId().equals("CN_UNIFIED_SOCIAL_CREDIT_CODE")
                        || match.ruleId().equals("IPV4_ADDRESS")));
    }

    @Test
    void resolvesOverlapsByPriorityWithoutMaskingPunctuation() {
        String text = "联系邮箱：alpha.beta@example.com";
        RedactionResult result = engine.redact(text);
        assertEquals(1, result.matches().size());
        assertEquals("EMAIL_ADDRESS", result.matches().getFirst().ruleId());
        assertTrue(result.redactedText().contains("＠") || result.redactedText().contains("@"));
        assertEquals(text.length(), result.redactedText().length());
    }

    @Test
    void persistsRuleSettingsAndAppliesBlackAndWhiteLists() throws Exception {
        Path settings = temp.resolve("rules.properties");
        RuleEngine configured = RuleEngine.createDefault(settings);
        configured.setEnabled("CN_DATE", false);
        configured.replaceBlacklist(List.of("内部代号青龙"));
        configured.replaceWhitelist(List.of("13800138000"));
        configured.addCustomRule("FILE_CODE", "文件编号", "custom", 80, "DOC-[0-9]{6}");

        RuleEngine reloaded = RuleEngine.createDefault(settings);
        String text = "日期2026-08-11，手机13800138000，内部代号青龙，编号DOC-123456";
        RedactionResult result = reloaded.redact(text);

        assertTrue(result.redactedText().contains("2026-08-11"));
        assertTrue(result.redactedText().contains("13800138000"));
        assertFalse(result.redactedText().contains("内部代号青龙"));
        assertFalse(result.redactedText().contains("DOC-123456"));
        assertFalse(reloaded.isEnabled("CN_DATE"));
        assertTrue(reloaded.rules().stream().anyMatch(rule -> rule.id().equals("CUSTOM_FILE_CODE")));
    }

    @Test
    void stablePseudonymsAreConsistentWithinProjectAndSeparatedAcrossProjects() {
        String text = "手机13800138000，备用手机13800138000";
        RuleEngine projectA = engine.withStablePseudonyms(new byte[32]);
        byte[] otherKey = new byte[32];
        java.util.Arrays.fill(otherKey, (byte) 7);
        RuleEngine projectB = engine.withStablePseudonyms(otherKey);

        String first = projectA.redact(text).redactedText();
        String second = projectA.redact(text).redactedText();
        String other = projectB.redact(text).redactedText();

        assertEquals(first, second);
        assertEquals(text.length(), first.length());
        assertFalse(first.contains("13800138000"));
        assertFalse(first.equals(other));
        String[] values = first.split("，备用手机");
        assertEquals(values[0].substring(2), values[1]);
    }

    @Test
    void editsTestsImportsAndRollsBackCustomRules() throws Exception {
        Path settings = temp.resolve("config/rules.properties");
        RuleEngine configured = RuleEngine.createDefault(settings);
        long baselineVersion = configured.version();
        assertTrue(configured.historyVersions().contains(baselineVersion));
        configured.addCustomRule("CASE_CODE", "案件代码", "legal_case", 70, "CASE-[0-9]{4}");
        long initialVersion = configured.version();
        configured.updateCustomRule("CUSTOM_CASE_CODE", "内部案件代码", "legal_case", 80,
                "DOC-[0-9]{6}");
        assertEquals(1, configured.testCustomRule("DOC-[0-9]{6}", "编号 DOC-123456").size());
        assertThrows(IllegalArgumentException.class,
                () -> configured.testCustomRule("(a+)+", "aaaaaaaa"));

        configured.importCustomRules(List.of(
                new CustomRuleSpec("SECOND", "第二规则", "custom", 40, "SEC-[A-Z]{3}")), false);
        assertTrue(configured.rules().stream().anyMatch(rule -> rule.id().equals("CUSTOM_SECOND")));
        assertTrue(configured.historyVersions().contains(initialVersion));

        configured.rollback(initialVersion);
        assertTrue(configured.rules().stream().anyMatch(rule -> rule.id().equals("CUSTOM_CASE_CODE")
                && rule.pattern().pattern().equals("CASE-[0-9]{4}")));
        assertFalse(configured.rules().stream().anyMatch(rule -> rule.id().equals("CUSTOM_SECOND")));

        configured.rollback(baselineVersion);
        assertFalse(configured.rules().stream().anyMatch(rule -> rule.id().startsWith("CUSTOM_")));
    }

    @Test
    void rejectsDuplicateMergeAndEmptyMatchingCustomRegex() throws Exception {
        RuleEngine configured = RuleEngine.createDefault(temp.resolve("rules.properties"));
        configured.addCustomRule("ONE", "规则一", "custom", 50, "ONE-[0-9]+");
        assertThrows(IllegalArgumentException.class,
                () -> configured.importCustomRules(List.of(
                        new CustomRuleSpec("ONE", "重复", "custom", 50, "TWO-[0-9]+")), false));
        assertThrows(IllegalArgumentException.class,
                () -> configured.addCustomRule("EMPTY", "空匹配", "custom", 50, "[0-9]*"));
    }

    @Test
    void customRegexUsesLinearTimeEngineAndRejectsUnsupportedLookaround() {
        String difficult = "a".repeat(19_000) + "!";
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertTrue(engine.testCustomRule("(a|aa)+$", difficult).isEmpty()));
        assertThrows(IllegalArgumentException.class,
                () -> engine.testCustomRule("(?=secret)secret", "secret"));
        assertThrows(IllegalArgumentException.class,
                () -> engine.testCustomRule("(secret)\\1", "secretsecret"));
    }

    static String validResidentId(String first17) {
        int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        char[] checks = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};
        int sum = 0;
        for (int i = 0; i < first17.length(); i++) sum += (first17.charAt(i) - '0') * weights[i];
        return first17 + checks[sum % 11];
    }

    static String validCreditCode(String first17) {
        String alphabet = "0123456789ABCDEFGHJKLMNPQRTUWXY";
        int[] weights = {1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28};
        int sum = 0;
        for (int i = 0; i < first17.length(); i++) sum += alphabet.indexOf(first17.charAt(i)) * weights[i];
        return first17 + alphabet.charAt((31 - sum % 31) % 31);
    }

    static String appendLuhnCheckDigit(String prefix) {
        for (int digit = 0; digit <= 9; digit++) {
            String candidate = prefix + digit;
            int sum = 0;
            boolean alternate = false;
            for (int i = candidate.length() - 1; i >= 0; i--) {
                int value = candidate.charAt(i) - '0';
                if (alternate) {
                    value *= 2;
                    if (value > 9) value -= 9;
                }
                sum += value;
                alternate = !alternate;
            }
            if (sum % 10 == 0) return candidate;
        }
        throw new IllegalStateException("No Luhn digit");
    }
}

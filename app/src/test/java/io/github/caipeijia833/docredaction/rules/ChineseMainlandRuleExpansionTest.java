/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.rules;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChineseMainlandRuleExpansionTest {
    private final RuleEngine engine = RuleEngine.createDefault();

    @Test
    void staysWithinTheApprovedRuleLibraryRange() {
        assertTrue(engine.rules().size() >= 180 && engine.rules().size() <= 250,
                () -> "unexpected built-in rule count: " + engine.rules().size());
    }

    @Test
    void detectsRepresentativeMainlandLegalFinancialMedicalAndPropertyFields() {
        String imei = luhn("86740002031675");
        String text = String.join("\n",
                "律师执业证号：14401202010203040",
                "病历号：MR-2026-000123",
                "银行账号：6222 0202 0100 1234 567",
                "联行号：102100099996",
                "保单号：POLICY-2026-998877",
                "卷宗编号：DOSSIER-2026-0001",
                "物证编号：EVIDENCE-88-2026",
                "不动产单元号：440106001001GB00001F00010001",
                "水费户号：WATER-00998877",
                "IMEI：" + imei,
                "经纬度：23.129110，113.264385");
        Set<String> ids = engine.detect(text).stream().map(SensitiveMatch::ruleId)
                .collect(Collectors.toSet());
        assertTrue(ids.containsAll(Set.of(
                "CN_LAWYER_LICENSE_CONTEXT", "CN_MEDICAL_RECORD_CONTEXT",
                "CN_BANK_ACCOUNT_CONTEXT", "CN_CNAPS_CODE_CONTEXT",
                "CN_INSURANCE_POLICY_CONTEXT", "CN_CASE_FILE_NUMBER_CONTEXT",
                "CN_EVIDENCE_NUMBER_CONTEXT", "CN_PROPERTY_UNIT_NUMBER_CONTEXT",
                "CN_UTILITY_ACCOUNT_CONTEXT", "IMEI_CONTEXT", "GPS_COORDINATES_CONTEXT")),
                ids.toString());
    }

    @Test
    void detectsIpv6CidrDottedMacAndContinents() {
        Set<String> ids = engine.detect("节点IPv6为2001:db8::1，网段2001:db8::/48，"
                        + "管理MAC为0011.2233.4455，业务覆盖亚洲和Europe。")
                .stream().map(SensitiveMatch::ruleId).collect(Collectors.toSet());
        assertTrue(ids.contains("IPV6_ADDRESS") || ids.contains("IPV6_CIDR"), ids.toString());
        assertTrue(ids.contains("MAC_ADDRESS_DOTTED"), ids.toString());
        assertTrue(ids.contains("GLOBAL_CONTINENT"), ids.toString());
    }

    @Test
    void rejectsInvalidDeviceAndDivisionIdentifiers() {
        assertNotDetected("IMEI_CONTEXT", "IMEI：867400020316750");
        assertNotDetected("CN_ADMIN_DIVISION_CODE_CONTEXT", "行政区划代码：999999");
        assertNotDetected("IPV6_ADDRESS", "版本号：2026:08");
    }

    private void assertNotDetected(String ruleId, String text) {
        assertFalse(engine.detect(text).stream().anyMatch(match -> match.ruleId().equals(ruleId)),
                () -> ruleId + " unexpectedly detected in: " + text);
    }

    private static String luhn(String prefix) {
        for (int digit = 0; digit <= 9; digit++) {
            String candidate = prefix + digit;
            int sum = 0;
            boolean doubleDigit = false;
            for (int index = candidate.length() - 1; index >= 0; index--) {
                int value = candidate.charAt(index) - '0';
                if (doubleDigit) {
                    value *= 2;
                    if (value > 9) value -= 9;
                }
                sum += value;
                doubleDigit = !doubleDigit;
            }
            if (sum % 10 == 0) return candidate;
        }
        throw new IllegalStateException("no Luhn check digit");
    }
}

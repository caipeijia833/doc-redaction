/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.rules;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternationalRuleEngineTest {
    private final RuleEngine engine = RuleEngine.createDefault();

    @Test
    void exposesTheCompleteBilingualAndInternationalBaseline() {
        assertEquals(205, engine.rules().size());
        Set<String> ids = engine.rules().stream().map(RuleDefinition::id).collect(Collectors.toSet());
        assertTrue(ids.containsAll(Set.of(
                "IBAN", "US_SSN", "UK_NINO", "DE_TAX_ID_CONTEXT", "FR_NIR_CONTEXT",
                "ES_DNI_NIE", "IT_CODICE_FISCALE", "NL_BSN_CONTEXT", "PL_PESEL_CONTEXT",
                "SE_PERSONNUMMER_CONTEXT", "NO_FODSELSNUMMER_CONTEXT", "FI_HETU",
                "AU_TFN_CONTEXT", "NZ_IRD_CONTEXT", "JP_MY_NUMBER_CONTEXT",
                "KR_RESIDENT_NUMBER", "SG_NRIC_FIN", "IN_AADHAAR", "HK_IDENTITY_CARD",
                "TW_NATIONAL_ID", "VEHICLE_REGISTRATION_CONTEXT", "PROPERTY_PARCEL_CONTEXT")));
    }

    @Test
    void detectsValidatedInternationalIdentityAndBankIdentifiers() {
        assertDetected("IBAN", "IBAN: GB82 WEST 1234 5698 7654 32");
        assertDetected("US_SSN", "SSN: 123-45-6789");
        assertDetected("US_ABA_ROUTING", "ABA routing number: 021000021");
        assertDetected("CANADA_SIN_CONTEXT", "Social Insurance Number: 046 454 286");
        assertDetected("UK_NINO", "National Insurance Number: AB 12 34 56 C");
        assertDetected("UK_NHS_NUMBER_CONTEXT", "NHS number: 943 476 5919");
        assertDetected("DE_TAX_ID_CONTEXT", "Steuer-ID: " + germanTaxId("1234567890"));
        assertDetected("FR_NIR_CONTEXT", "NIR: " + frenchNir("1841275123456"));
        assertDetected("ES_DNI_NIE", "DNI: 12345678Z");
        assertDetected("IT_CODICE_FISCALE", "Codice fiscale: RSSMRA85T10A562S");
        assertDetected("NL_BSN_CONTEXT", "BSN: 111222333");
        assertDetected("PL_PESEL_CONTEXT", "PESEL: 44051401458");
        assertDetected("SE_PERSONNUMMER_CONTEXT", "Personnummer: 811218-9876");
        assertDetected("NO_FODSELSNUMMER_CONTEXT", "Fødselsnummer: " + norwegianNumber());
        assertDetected("FI_HETU", "HETU: 131052-308T");
        assertDetected("AU_TFN_CONTEXT", "Tax File Number: 123 456 782");
        assertDetected("NZ_IRD_CONTEXT", "IRD number: " + nzIrd("4909657"));
        assertDetected("JP_MY_NUMBER_CONTEXT", "My Number: " + japanMyNumber("12345678901"));
        assertDetected("KR_RESIDENT_NUMBER", "Resident registration number: " + koreanNumber("900101123456"));
        assertDetected("SG_NRIC_FIN", "NRIC: S1234567D");
        assertDetected("IN_AADHAAR", "Aadhaar: " + aadhaar("23456789012"));
        assertDetected("HK_IDENTITY_CARD", "HKID: A123456(3)");
        assertDetected("TW_NATIONAL_ID", "National ID: A123456789");
    }

    @Test
    void rejectsRepresentativeInvalidChecksums() {
        assertNotDetected("IBAN", "IBAN: GB82 WEST 1234 5698 7654 31");
        assertNotDetected("US_ABA_ROUTING", "ABA routing number: 021000022");
        assertNotDetected("CANADA_SIN_CONTEXT", "SIN: 046 454 287");
        assertNotDetected("ES_DNI_NIE", "DNI: 12345678A");
        assertNotDetected("NL_BSN_CONTEXT", "BSN: 111222334");
        assertNotDetected("PL_PESEL_CONTEXT", "PESEL: 44051401459");
        assertNotDetected("SG_NRIC_FIN", "NRIC: S1234567A");
        assertNotDetected("HK_IDENTITY_CARD", "HKID: A123456(4)");
        assertNotDetected("TW_NATIONAL_ID", "National ID: A123456788");
    }

    @Test
    void detectsEnglishNamesAddressesPhonesAndContextBoundAssets() {
        String text = String.join("\n",
                "Full name: Alice Johnson",
                "Residential address: 10 Downing Street, London SW1A 2AA",
                "Mobile: +44 (0) 7700 900123",
                "Company name: Example Holdings Limited",
                "Vehicle registration number: AB12 CDE",
                "Engine number: ENG-9081726",
                "Parcel ID: 01-234-567-890",
                "Land registry title number: NGL-123456",
                "Mortgage account: MTG-88776655",
                "Bank account number: 001234567890");
        Set<String> ids = engine.detect(text).stream().map(SensitiveMatch::ruleId).collect(Collectors.toSet());
        assertTrue(ids.containsAll(Set.of("EN_PERSON_NAME", "EN_POSTAL_ADDRESS", "INTERNATIONAL_PHONE",
                "EN_ORGANIZATION_NAME", "VEHICLE_REGISTRATION_CONTEXT", "VEHICLE_ENGINE_NUMBER_CONTEXT",
                "PROPERTY_PARCEL_CONTEXT", "LAND_REGISTRY_TITLE_CONTEXT", "MORTGAGE_ACCOUNT_CONTEXT",
                "GENERIC_BANK_ACCOUNT_CONTEXT")), ids.toString());

        assertFalse(engine.detect("Reference AB12 CDE and batch 01-234-567-890")
                .stream().anyMatch(match -> match.ruleId().equals("VEHICLE_REGISTRATION_CONTEXT")
                        || match.ruleId().equals("PROPERTY_PARCEL_CONTEXT")));
        assertNotDetected("EN_ORGANIZATION_NAME",
                "WHOLLY SYNTHETIC STRESS DATA; NO REAL PERSON, COMPANY OR CASE");
        assertNotDetected("IT_CODICE_FISCALE", "random checksum-like token e020c7359e53319e");
        String aadhaar = aadhaar("23456789012");
        assertNotDetected("IN_AADHAAR", "unlabelled numeric reference " + aadhaar);
        assertDetected("IN_AADHAAR", "Aadhaar: " + aadhaar);
    }

    private void assertDetected(String ruleId, String text) {
        assertTrue(engine.detect(text).stream().anyMatch(match -> match.ruleId().equals(ruleId)),
                () -> ruleId + " not detected in: " + text);
    }

    private void assertNotDetected(String ruleId, String text) {
        assertFalse(engine.detect(text).stream().anyMatch(match -> match.ruleId().equals(ruleId)),
                () -> ruleId + " unexpectedly detected in: " + text);
    }

    private static String germanTaxId(String firstTen) {
        int product = 10;
        for (int i = 0; i < firstTen.length(); i++) {
            int sum = ((firstTen.charAt(i) - '0') + product) % 10;
            if (sum == 0) sum = 10;
            product = (2 * sum) % 11;
        }
        int check = 11 - product;
        if (check == 10) check = 0;
        return firstTen + check;
    }

    private static String frenchNir(String firstThirteen) {
        int check = 97 - new BigInteger(firstThirteen).mod(BigInteger.valueOf(97)).intValue();
        return firstThirteen + String.format("%02d", check);
    }

    private static String norwegianNumber() {
        for (int individual = 0; individual <= 999; individual++) {
            String firstNine = "010101" + String.format("%03d", individual);
            int first = modulus11(firstNine, new int[] {3, 7, 6, 1, 8, 9, 4, 5, 2});
            if (first < 0) continue;
            String firstTen = firstNine + first;
            int second = modulus11(firstTen, new int[] {5, 4, 3, 2, 7, 6, 5, 4, 3, 2});
            if (second >= 0) return firstTen + second;
        }
        throw new IllegalStateException("No Norwegian test fixture");
    }

    private static int modulus11(String digits, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) sum += (digits.charAt(i) - '0') * weights[i];
        int value = 11 - sum % 11;
        if (value == 11) return 0;
        return value == 10 ? -1 : value;
    }

    private static String japanMyNumber(String firstEleven) {
        int sum = 0;
        for (int n = 1; n <= 11; n++) {
            int digit = firstEleven.charAt(11 - n) - '0';
            int weight = n <= 6 ? n + 1 : n - 5;
            sum += digit * weight;
        }
        int remainder = sum % 11;
        return firstEleven + (remainder <= 1 ? 0 : 11 - remainder);
    }

    private static String nzIrd(String prefix) {
        for (int digit = 0; digit <= 9; digit++) {
            String candidate = prefix + digit;
            if (Validators.newZealandIrd(candidate, candidate, 0, candidate.length())) return candidate;
        }
        throw new IllegalStateException("No IRD test fixture");
    }

    private static String koreanNumber(String firstTwelve) {
        int[] weights = {2, 3, 4, 5, 6, 7, 8, 9, 2, 3, 4, 5};
        int sum = 0;
        for (int i = 0; i < firstTwelve.length(); i++) sum += (firstTwelve.charAt(i) - '0') * weights[i];
        return firstTwelve + ((11 - sum % 11) % 10);
    }

    private static String aadhaar(String firstEleven) {
        for (int digit = 0; digit <= 9; digit++) {
            String candidate = firstEleven + digit;
            if (Validators.indiaAadhaar(candidate, candidate, 0, candidate.length())) return candidate;
        }
        throw new IllegalStateException("No Aadhaar test fixture");
    }
}

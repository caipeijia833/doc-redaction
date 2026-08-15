/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.rules;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.math.BigInteger;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class Validators {
    private static final int[] ID_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] ID_CHECK = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};
    private static final String USCC_CHARS = "0123456789ABCDEFGHJKLMNPQRTUWXY";
    private static final int[] USCC_WEIGHTS = {1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28};
    private static final String SPANISH_ID_CHECK = "TRWAGMYFPDXBNJZSQVHLCKE";
    private static final String FINNISH_ID_CHECK = "0123456789ABCDEFHJKLMNPRSTUVWXY";
    private static final int[][] VERHOEFF_D = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 2, 3, 4, 0, 6, 7, 8, 9, 5},
            {2, 3, 4, 0, 1, 7, 8, 9, 5, 6}, {3, 4, 0, 1, 2, 8, 9, 5, 6, 7},
            {4, 0, 1, 2, 3, 9, 5, 6, 7, 8}, {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
            {6, 5, 9, 8, 7, 1, 0, 4, 3, 2}, {7, 6, 5, 9, 8, 2, 1, 0, 4, 3},
            {8, 7, 6, 5, 9, 3, 2, 1, 0, 4}, {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}
    };
    private static final int[][] VERHOEFF_P = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 5, 7, 6, 2, 8, 3, 0, 9, 4},
            {5, 8, 0, 3, 7, 9, 6, 1, 4, 2}, {8, 9, 1, 6, 0, 4, 3, 5, 2, 7},
            {9, 4, 5, 3, 1, 2, 6, 8, 7, 0}, {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
            {2, 7, 9, 3, 8, 0, 6, 4, 1, 5}, {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}
    };
    private static final Map<Character, Integer> TAIWAN_LETTER_CODES = Map.ofEntries(
            Map.entry('A', 10), Map.entry('B', 11), Map.entry('C', 12), Map.entry('D', 13),
            Map.entry('E', 14), Map.entry('F', 15), Map.entry('G', 16), Map.entry('H', 17),
            Map.entry('I', 34), Map.entry('J', 18), Map.entry('K', 19), Map.entry('L', 20),
            Map.entry('M', 21), Map.entry('N', 22), Map.entry('O', 35), Map.entry('P', 23),
            Map.entry('Q', 24), Map.entry('R', 25), Map.entry('S', 26), Map.entry('T', 27),
            Map.entry('U', 28), Map.entry('V', 29), Map.entry('W', 32), Map.entry('X', 30),
            Map.entry('Y', 31), Map.entry('Z', 33));

    private Validators() {
    }

    static boolean always(String value, String fullText, int start, int end) {
        return value != null && !value.isBlank();
    }

    static boolean chineseResidentId(String value, String fullText, int start, int end) {
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!normalized.matches("[1-9]\\d{16}[0-9X]")) {
            return false;
        }
        try {
            int year = Integer.parseInt(normalized.substring(6, 10));
            int month = Integer.parseInt(normalized.substring(10, 12));
            int day = Integer.parseInt(normalized.substring(12, 14));
            LocalDate.of(year, month, day);
        } catch (DateTimeException | NumberFormatException ex) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (normalized.charAt(i) - '0') * ID_WEIGHTS[i];
        }
        return ID_CHECK[sum % 11] == normalized.charAt(17);
    }

    static boolean chineseResidentId15(String value, String fullText, int start, int end) {
        String normalized = digits(value);
        if (!normalized.matches("[1-9]\\d{14}")) {
            return false;
        }
        try {
            int year = 1900 + Integer.parseInt(normalized.substring(6, 8));
            int month = Integer.parseInt(normalized.substring(8, 10));
            int day = Integer.parseInt(normalized.substring(10, 12));
            LocalDate.of(year, month, day);
            return true;
        } catch (DateTimeException | NumberFormatException ex) {
            return false;
        }
    }

    static boolean luhn(String value, String fullText, int start, int end) {
        String digits = digits(value);
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        return luhnDigits(digits);
    }

    static boolean canadianSin(String value, String fullText, int start, int end) {
        String normalized = digits(value);
        return normalized.length() == 9 && !normalized.chars().allMatch(ch -> ch == '0')
                && luhnDigits(normalized);
    }

    static boolean internationalPhone(String value, String fullText, int start, int end) {
        String normalized = digits(value);
        return normalized.length() >= 8 && normalized.length() <= 15
                && !normalized.chars().allMatch(ch -> ch == '0');
    }

    static boolean usSsn(String value, String fullText, int start, int end) {
        String normalized = digits(value);
        if (normalized.length() != 9) {
            return false;
        }
        int area = Integer.parseInt(normalized.substring(0, 3));
        return area != 0 && area != 666 && area < 900
                && !normalized.substring(3, 5).equals("00")
                && !normalized.substring(5).equals("0000");
    }

    static boolean iban(String value, String fullText, int start, int end) {
        String normalized = value.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{2}\\d{2}[A-Z0-9]{11,30}")) {
            return false;
        }
        String rearranged = normalized.substring(4) + normalized.substring(0, 4);
        int remainder = 0;
        for (int i = 0; i < rearranged.length(); i++) {
            char current = rearranged.charAt(i);
            String numeric = Character.isDigit(current)
                    ? String.valueOf(current) : String.valueOf(current - 'A' + 10);
            for (int j = 0; j < numeric.length(); j++) {
                remainder = (remainder * 10 + numeric.charAt(j) - '0') % 97;
            }
        }
        return remainder == 1;
    }

    static boolean abaRouting(String value, String fullText, int start, int end) {
        String normalized = digits(value);
        if (normalized.length() != 9 || normalized.chars().allMatch(ch -> ch == '0')) {
            return false;
        }
        int checksum = 3 * ((normalized.charAt(0) - '0') + (normalized.charAt(3) - '0')
                + (normalized.charAt(6) - '0'))
                + 7 * ((normalized.charAt(1) - '0') + (normalized.charAt(4) - '0')
                + (normalized.charAt(7) - '0'))
                + (normalized.charAt(2) - '0') + (normalized.charAt(5) - '0')
                + (normalized.charAt(8) - '0');
        return checksum % 10 == 0 && hasContext(fullText, start, end,
                "routing", "aba", "transit number", "联行号", "路由号码");
    }

    static boolean ukNationalInsurance(String value, String fullText, int start, int end) {
        String normalized = value.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{2}\\d{6}[A-D]?")) {
            return false;
        }
        String prefix = normalized.substring(0, 2);
        return "DFIQUV".indexOf(prefix.charAt(0)) < 0
                && "DFIOQUV".indexOf(prefix.charAt(1)) < 0
                && !Set.of("BG", "GB", "KN", "NK", "NT", "TN", "ZZ").contains(prefix);
    }

    static boolean ukNhs(String value, String fullText, int start, int end) {
        String normalized = digits(value);
        if (normalized.length() != 10) {
            return false;
        }
        int total = 0;
        for (int i = 0; i < 9; i++) {
            total += (normalized.charAt(i) - '0') * (10 - i);
        }
        int check = 11 - total % 11;
        if (check == 11) check = 0;
        return check != 10 && check == normalized.charAt(9) - '0';
    }

    static boolean germanTaxId(String value, String fullText, int start, int end) {
        String normalized = digits(value);
        if (normalized.length() != 11 || normalized.charAt(0) == '0') {
            return false;
        }
        int product = 10;
        for (int i = 0; i < 10; i++) {
            int sum = ((normalized.charAt(i) - '0') + product) % 10;
            if (sum == 0) sum = 10;
            product = (2 * sum) % 11;
        }
        int check = 11 - product;
        if (check == 10) check = 0;
        return check == normalized.charAt(10) - '0';
    }

    static boolean frenchNir(String value, String fullText, int start, int end) {
        String normalized = digits(value);
        if (normalized.length() != 15 || (normalized.charAt(0) != '1' && normalized.charAt(0) != '2')) {
            return false;
        }
        BigInteger base = new BigInteger(normalized.substring(0, 13));
        int expected = 97 - base.mod(BigInteger.valueOf(97)).intValue();
        return expected == Integer.parseInt(normalized.substring(13));
    }

    static boolean spanishDniNie(String value, String fullText, int start, int end) {
        String normalized = value.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (!normalized.matches("(?:\\d{8}|[XYZ]\\d{7})[A-Z]")) {
            return false;
        }
        String number = normalized.substring(0, normalized.length() - 1)
                .replace('X', '0').replace('Y', '1').replace('Z', '2');
        char expected = SPANISH_ID_CHECK.charAt(Integer.parseInt(number) % 23);
        return expected == normalized.charAt(normalized.length() - 1);
    }

    static boolean italianFiscalCode(String value, String fullText, int start, int end) {
        String normalized = value.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{6}\\d{2}[ABCDEHLMPRST]\\d{2}[A-Z]\\d{3}[A-Z]")) {
            return false;
        }
        String oddChars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        int[] oddValues = {1, 0, 5, 7, 9, 13, 15, 17, 19, 21,
                1, 0, 5, 7, 9, 13, 15, 17, 19, 21, 2, 4, 18, 20, 11, 3, 6, 8, 12, 14, 16, 10, 22, 25, 24, 23};
        int sum = 0;
        for (int i = 0; i < 15; i++) {
            char current = normalized.charAt(i);
            if ((i & 1) == 0) {
                sum += oddValues[oddChars.indexOf(current)];
            } else {
                sum += Character.isDigit(current) ? current - '0' : current - 'A';
            }
        }
        return (char) ('A' + sum % 26) == normalized.charAt(15);
    }

    static boolean dutchBsn(String value, String fullText, int start, int end) {
        String normalized = digits(value);
        if (normalized.length() != 9 || normalized.chars().allMatch(ch -> ch == '0')) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 8; i++) sum += (normalized.charAt(i) - '0') * (9 - i);
        sum -= normalized.charAt(8) - '0';
        return sum % 11 == 0;
    }

    static boolean polishPesel(String value, String fullText, int start, int end) {
        String normalized = digits(value);
        if (normalized.length() != 11) return false;
        int[] weights = {1, 3, 7, 9, 1, 3, 7, 9, 1, 3};
        int sum = 0;
        for (int i = 0; i < 10; i++) sum += (normalized.charAt(i) - '0') * weights[i];
        return (10 - sum % 10) % 10 == normalized.charAt(10) - '0';
    }

    static boolean swedishPersonNumber(String value, String fullText, int start, int end) {
        String normalized = digits(value);
        if (normalized.length() == 12) normalized = normalized.substring(2);
        return normalized.length() == 10 && validShortDate(normalized.substring(0, 6))
                && luhnDigits(normalized);
    }

    static boolean norwegianBirthNumber(String value, String fullText, int start, int end) {
        String normalized = digits(value);
        if (normalized.length() != 11 || !validDayMonth(normalized.substring(0, 4))) return false;
        int[] firstWeights = {3, 7, 6, 1, 8, 9, 4, 5, 2};
        int first = modulus11Digit(normalized, firstWeights);
        if (first < 0 || first != normalized.charAt(9) - '0') return false;
        int[] secondWeights = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};
        int second = modulus11Digit(normalized, secondWeights);
        return second >= 0 && second == normalized.charAt(10) - '0';
    }

    static boolean finnishPersonalCode(String value, String fullText, int start, int end) {
        String normalized = value.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (!normalized.matches("\\d{6}[+\\-ABCDEFYXWVU]\\d{3}[0-9A-Z]")) return false;
        if (!validDayMonth(normalized.substring(0, 4))) return false;
        int number = Integer.parseInt(normalized.substring(0, 6) + normalized.substring(7, 10));
        return FINNISH_ID_CHECK.charAt(number % 31) == normalized.charAt(10);
    }

    static boolean australianTfn(String value, String fullText, int start, int end) {
        String normalized = digits(value);
        if (normalized.length() == 8) normalized = "0" + normalized;
        if (normalized.length() != 9) return false;
        int[] weights = {1, 4, 3, 7, 5, 8, 6, 9, 10};
        int sum = 0;
        for (int i = 0; i < 9; i++) sum += (normalized.charAt(i) - '0') * weights[i];
        return sum % 11 == 0;
    }

    static boolean newZealandIrd(String value, String fullText, int start, int end) {
        String normalized = digits(value);
        if (normalized.length() == 8) normalized = "0" + normalized;
        if (normalized.length() != 9) return false;
        int[] primary = {3, 2, 7, 6, 5, 4, 3, 2};
        int check = nzCheckDigit(normalized, primary);
        if (check == 10) {
            int[] secondary = {7, 4, 3, 2, 5, 2, 7, 6};
            check = nzCheckDigit(normalized, secondary);
        }
        return check >= 0 && check < 10 && check == normalized.charAt(8) - '0';
    }

    static boolean japanMyNumber(String value, String fullText, int start, int end) {
        String normalized = digits(value);
        if (normalized.length() != 12) return false;
        int sum = 0;
        for (int n = 1; n <= 11; n++) {
            int digit = normalized.charAt(11 - n) - '0';
            int weight = n <= 6 ? n + 1 : n - 5;
            sum += digit * weight;
        }
        int remainder = sum % 11;
        int check = remainder <= 1 ? 0 : 11 - remainder;
        return check == normalized.charAt(11) - '0';
    }

    static boolean koreanResidentNumber(String value, String fullText, int start, int end) {
        String normalized = digits(value);
        if (normalized.length() != 13 || !validShortDate(normalized.substring(0, 6))) return false;
        int[] weights = {2, 3, 4, 5, 6, 7, 8, 9, 2, 3, 4, 5};
        int sum = 0;
        for (int i = 0; i < 12; i++) sum += (normalized.charAt(i) - '0') * weights[i];
        return (11 - sum % 11) % 10 == normalized.charAt(12) - '0';
    }

    static boolean singaporeNric(String value, String fullText, int start, int end) {
        String normalized = value.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[STFGM]\\d{7}[A-Z]")) return false;
        int[] weights = {2, 7, 6, 5, 4, 3, 2};
        int sum = 0;
        for (int i = 0; i < 7; i++) sum += (normalized.charAt(i + 1) - '0') * weights[i];
        char prefix = normalized.charAt(0);
        if (prefix == 'T' || prefix == 'G') sum += 4;
        if (prefix == 'M') sum += 3;
        String checks = switch (prefix) {
            case 'S', 'T' -> "JZIHGFEDCBA";
            case 'F', 'G' -> "XWUTRQPNMLK";
            default -> "KLJNPQRTUWX";
        };
        return checks.charAt(sum % 11) == normalized.charAt(8);
    }

    static boolean indiaAadhaar(String value, String fullText, int start, int end) {
        String normalized = digits(value);
        if (normalized.length() != 12 || normalized.charAt(0) < '2') return false;
        int checksum = 0;
        for (int i = 0; i < normalized.length(); i++) {
            int digit = normalized.charAt(normalized.length() - 1 - i) - '0';
            checksum = VERHOEFF_D[checksum][VERHOEFF_P[i % 8][digit]];
        }
        return checksum == 0;
    }

    static boolean indiaAadhaarContext(String value, String fullText, int start, int end) {
        return indiaAadhaar(value, fullText, start, end)
                && hasContext(fullText, start, end, "aadhaar", "uidai", "आधार", "印度身份号码");
    }

    static boolean hongKongId(String value, String fullText, int start, int end) {
        String normalized = value.replaceAll("[\\s()]", "").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{1,2}\\d{6}[0-9A]")) return false;
        int offset = normalized.length() == 8 ? 1 : 0;
        int sum = offset == 1 ? 36 * 9 : letterValue(normalized.charAt(0)) * 9;
        char second = normalized.charAt(offset == 1 ? 0 : 1);
        sum += letterValue(second) * 8;
        int digitsStart = offset == 1 ? 1 : 2;
        for (int i = 0; i < 6; i++) sum += (normalized.charAt(digitsStart + i) - '0') * (7 - i);
        char checkChar = normalized.charAt(normalized.length() - 1);
        sum += checkChar == 'A' ? 10 : checkChar - '0';
        return sum % 11 == 0;
    }

    static boolean taiwanNationalId(String value, String fullText, int start, int end) {
        String normalized = value.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][12]\\d{8}")) return false;
        Integer code = TAIWAN_LETTER_CODES.get(normalized.charAt(0));
        if (code == null) return false;
        int sum = code / 10 + (code % 10) * 9;
        for (int i = 1; i <= 8; i++) sum += (normalized.charAt(i) - '0') * (9 - i);
        sum += normalized.charAt(9) - '0';
        return sum % 10 == 0;
    }

    static boolean vinContext(String value, String fullText, int start, int end) {
        String normalized = value.toUpperCase(Locale.ROOT);
        return normalized.matches("[A-HJ-NPR-Z0-9]{17}")
                && hasContext(fullText, start, end, "vin", "vehicle identification", "chassis",
                "车架号", "车辆识别代号", "차대번호", "車台番号");
    }

    static boolean contextRequired(String value, String fullText, int start, int end, String... keywords) {
        return always(value, fullText, start, end) && hasContext(fullText, start, end, keywords);
    }

    private static boolean luhnDigits(String digits) {
        int sum = 0;
        boolean doubleDigit = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }

    static boolean unifiedSocialCreditCode(String value, String fullText, int start, int end) {
        String normalized = value.toUpperCase(Locale.ROOT);
        if (normalized.length() != 18) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            int index = USCC_CHARS.indexOf(normalized.charAt(i));
            if (index < 0) {
                return false;
            }
            sum += index * USCC_WEIGHTS[i];
        }
        int checkIndex = (31 - (sum % 31)) % 31;
        return USCC_CHARS.charAt(checkIndex) == normalized.charAt(17);
    }

    static boolean ipv4(String value, String fullText, int start, int end) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || (part.length() > 1 && part.startsWith("0"))) {
                return false;
            }
            try {
                int number = Integer.parseInt(part);
                if (number < 0 || number > 255) {
                    return false;
                }
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        return true;
    }

    static boolean ipv6(String value, String fullText, int start, int end) {
        if (value == null || !value.contains(":")) {
            return false;
        }
        try {
            return InetAddress.getByName(value) instanceof Inet6Address;
        } catch (UnknownHostException ex) {
            return false;
        }
    }

    static boolean ipv4Cidr(String value, String fullText, int start, int end) {
        if (value == null) return false;
        String[] parts = value.split("/", -1);
        if (parts.length != 2 || !ipv4(parts[0], fullText, start, end)) return false;
        try {
            int prefix = Integer.parseInt(parts[1]);
            return prefix >= 0 && prefix <= 32;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    static boolean ipv6Cidr(String value, String fullText, int start, int end) {
        if (value == null) return false;
        int separator = value.lastIndexOf('/');
        if (separator <= 0 || !ipv6(value.substring(0, separator), fullText, start, separator)) {
            return false;
        }
        try {
            int prefix = Integer.parseInt(value.substring(separator + 1));
            return prefix >= 0 && prefix <= 128;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    static boolean imei(String value, String fullText, int start, int end) {
        String normalized = digits(value);
        return normalized.length() == 15 && !normalized.chars().allMatch(ch -> ch == '0')
                && luhnDigits(normalized);
    }

    static boolean iccid(String value, String fullText, int start, int end) {
        String normalized = digits(value);
        return (normalized.length() == 19 || normalized.length() == 20)
                && normalized.startsWith("89") && luhnDigits(normalized);
    }

    static boolean coordinates(String value, String fullText, int start, int end) {
        if (value == null) return false;
        String[] fields = value.trim().split("[,，\\s]+", -1);
        if (fields.length != 2) return false;
        try {
            double first = Double.parseDouble(fields[0]);
            double second = Double.parseDouble(fields[1]);
            return (Math.abs(first) <= 90 && Math.abs(second) <= 180)
                    || (Math.abs(first) <= 180 && Math.abs(second) <= 90);
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    static boolean chineseAdministrativeDivisionCode(String value, String fullText, int start, int end) {
        return ChineseAdministrativeDivisionLexicon.instance().isKnownCode(value);
    }

    static RuleValidator context(String... keywords) {
        return (value, fullText, start, end) -> contextRequired(value, fullText, start, end, keywords);
    }

    private static boolean hasContext(String fullText, int start, int end, String... keywords) {
        int left = Math.max(0, start - 32);
        int right = Math.min(fullText.length(), end + 32);
        String nearby = fullText.substring(left, right).toLowerCase(Locale.ROOT);
        return Set.of(keywords).stream().map(s -> s.toLowerCase(Locale.ROOT)).anyMatch(nearby::contains);
    }

    private static String digits(String value) {
        return value == null ? "" : value.replaceAll("[^0-9]", "");
    }

    private static boolean validShortDate(String yymmdd) {
        if (!yymmdd.matches("\\d{6}")) return false;
        try {
            int year = Integer.parseInt(yymmdd.substring(0, 2));
            int month = Integer.parseInt(yymmdd.substring(2, 4));
            int day = Integer.parseInt(yymmdd.substring(4, 6));
            LocalDate.of(2000 + year, month, day);
            return true;
        } catch (DateTimeException | NumberFormatException ex) {
            return false;
        }
    }

    private static boolean validDayMonth(String ddmm) {
        if (!ddmm.matches("\\d{4}")) return false;
        try {
            LocalDate.of(2000, Integer.parseInt(ddmm.substring(2, 4)), Integer.parseInt(ddmm.substring(0, 2)));
            return true;
        } catch (DateTimeException | NumberFormatException ex) {
            return false;
        }
    }

    private static int modulus11Digit(String digits, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) sum += (digits.charAt(i) - '0') * weights[i];
        int remainder = sum % 11;
        int result = remainder == 0 ? 0 : 11 - remainder;
        return result == 10 ? -1 : result;
    }

    private static int nzCheckDigit(String value, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) sum += (value.charAt(i) - '0') * weights[i];
        int remainder = sum % 11;
        return remainder == 0 ? 0 : 11 - remainder;
    }

    private static int letterValue(char value) {
        return value - 'A' + 10;
    }
}

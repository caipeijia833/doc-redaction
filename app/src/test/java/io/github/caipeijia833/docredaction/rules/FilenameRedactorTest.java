/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.rules;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilenameRedactorTest {
    private final RuleEngine rules = RuleEngine.createDefault();

    @Test
    void redactsSensitiveStemAndPreservesCompoundExtension() {
        var preview = FilenameRedactor.previewOutputName(
                "身份证_11010519491231002X_证据.tar.gz", rules);

        assertFalse(preview.redactedName().contains("11010519491231002X"));
        assertTrue(preview.redactedName().contains("REDACTED"));
        assertTrue(preview.redactedName().endsWith("_脱敏.tar.gz"));
    }

    @Test
    void redactsArchiveDirectoriesAndAllocatesCollisionSuffix() {
        String path = FilenameRedactor.redactArchivePath(
                "手机号13800138000/身份证11010519491231002X.docx", rules);
        assertFalse(path.contains("13800138000"));
        assertFalse(path.contains("11010519491231002X"));
        assertTrue(path.endsWith(".docx"));

        Set<String> used = new HashSet<>();
        assertEquals(path, FilenameRedactor.allocateUniquePath(path, used));
        assertTrue(FilenameRedactor.allocateUniquePath(path, used).endsWith(" (2).docx"));
    }

    @Test
    void batchPreviewUsesCaseInsensitiveWindowsCollisionRules() {
        List<FilenameRedactor.Preview> previews = FilenameRedactor.allocatePreviews(
                List.of("手机号13800138000.pdf", "手机号13800138000.PDF"), rules, Set.of());

        assertEquals(2, previews.size());
        assertTrue(previews.get(1).redactedName().contains(" (2)"));
    }

    @Test
    void avoidsWindowsReservedNamesAndNeverSplitsUnicodeCodePoints() {
        assertTrue(FilenameRedactor.previewOutputName("CON.docx", rules).redactedName().startsWith("_CON"));
        assertTrue(FilenameRedactor.previewOutputName("aux", rules).redactedName().startsWith("_aux"));
        assertTrue(FilenameRedactor.previewOutputName("COM1.pdf", rules).redactedName().startsWith("_COM1"));

        String original = "😀".repeat(220) + ".pdf";
        String safe = FilenameRedactor.previewOutputName(original, rules).originalName();
        assertTrue(safe.codePointCount(0, safe.length()) <= 180);
        for (int index = 0; index < safe.length(); index++) {
            char current = safe.charAt(index);
            if (Character.isHighSurrogate(current)) {
                assertTrue(index + 1 < safe.length() && Character.isLowSurrogate(safe.charAt(index + 1)));
                index++;
            } else {
                assertFalse(Character.isLowSurrogate(current));
            }
        }
    }
}

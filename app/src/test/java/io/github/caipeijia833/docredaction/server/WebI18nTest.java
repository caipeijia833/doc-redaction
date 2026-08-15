/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.server;

import io.github.caipeijia833.docredaction.rules.RuleEngine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class WebI18nTest {
    private static final Pattern HTML_KEY = Pattern.compile(
            "data-i18n(?:-placeholder|-aria-label|-value)?=\"([^\"]+)\"");
    private static final Pattern JS_LITERAL_KEY = Pattern.compile("\\bt\\(['\"]([^'\"]+)['\"]");
    private static final Pattern HAN = Pattern.compile("[\\u3400-\\u9fff]");

    @Test
    void everyStaticAndLiteralUiKeyHasChineseAndEnglishEntries() throws Exception {
        String html = resource("web/index.html");
        String application = resource("web/app.js");
        String localization = resource("web/i18n.js");
        Set<String> keys = new LinkedHashSet<>();
        collect(HTML_KEY, html, keys);
        collect(JS_LITERAL_KEY, application, keys);

        for (String key : keys) {
            int occurrences = count(localization, "'" + key + "':");
            assertEquals(2, occurrences, () -> "Missing zh-CN or en entry for UI key " + key);
        }
        assertTrue(html.contains("data-language=\"zh-CN\""));
        assertTrue(html.contains("data-language=\"en\""));
        assertTrue(localization.contains("doc-redaction-ui-language"));
        assertTrue(localization.contains("navigator.language"));
        assertTrue(localization.contains("if (normalized.startsWith('en')) return 'en';"));
        assertTrue(localization.contains("return 'zh-CN';"),
                "Unsupported browser locales must fall back to Chinese");
    }

    @Test
    void applicationLogicContainsNoHardCodedChineseUiCopy() throws Exception {
        String application = resource("web/app.js");
        assertFalse(HAN.matcher(application).find(),
                "Dynamic UI copy must come from the bilingual catalog, not app.js");
    }

    @Test
    void everyBuiltInRuleHasAnEnglishLabel() throws Exception {
        String localization = resource("web/i18n.js");
        RuleEngine.createDefault().rules().forEach(rule ->
                assertTrue(localization.contains(rule.id() + ":"),
                        () -> "Missing English rule label for " + rule.id()));
    }

    @Test
    void batchWizardRequiresCapacityCheckAndExplicitArchiveDecisions() throws Exception {
        String html = resource("web/index.html");
        String application = resource("web/app.js");

        assertTrue(html.contains("id=\"capacity-estimate\""));
        assertTrue(html.contains("id=\"wizard-confirm-files\""));
        assertTrue(html.contains("role=\"progressbar\""));
        assertTrue(application.contains("api('/api/capacity-estimate'"));
        assertTrue(application.contains("removeSelectedFile"));
        assertTrue(application.contains("KEEP_UNPROCESSED"));
        assertTrue(application.contains("JSON.stringify({ riskConfirmation, decisions:"));
        assertFalse(application.contains("setFiles(event.target.files, true)"),
                "Selecting files must not skip directly to the next wizard step");
    }

    private static void collect(Pattern pattern, String value, Set<String> output) {
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) output.add(matcher.group(1));
    }

    private static int count(String value, String needle) {
        int count = 0;
        for (int index = 0; (index = value.indexOf(needle, index)) >= 0; index += needle.length()) count++;
        return count;
    }

    private static String resource(String name) throws IOException {
        try (InputStream input = WebI18nTest.class.getClassLoader().getResourceAsStream(name)) {
            if (input == null) throw new IOException("Missing test resource " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

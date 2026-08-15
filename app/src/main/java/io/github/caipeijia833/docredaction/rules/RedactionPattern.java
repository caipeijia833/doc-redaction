/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.rules;

import java.util.regex.Pattern;

/**
 * Adapts reviewed built-in rules and linear-time untrusted user rules to one API.
 */
public final class RedactionPattern {
    private final String source;
    private final Pattern javaPattern;
    private final com.google.re2j.Pattern linearPattern;

    private RedactionPattern(String source, Pattern javaPattern, com.google.re2j.Pattern linearPattern) {
        this.source = source;
        this.javaPattern = javaPattern;
        this.linearPattern = linearPattern;
    }

    static RedactionPattern reviewed(String regex, int flags) {
        return new RedactionPattern(regex, Pattern.compile(regex, flags), null);
    }

    static RedactionPattern untrustedLinear(String regex) {
        com.google.re2j.Pattern compiled = com.google.re2j.Pattern.compile(
                regex, com.google.re2j.Pattern.CASE_INSENSITIVE);
        return new RedactionPattern(regex, null, compiled);
    }

    public String pattern() {
        return source;
    }

    Match matcher(String text) {
        return javaPattern != null
                ? new Match(javaPattern.matcher(text), null)
                : new Match(null, linearPattern.matcher(text));
    }

    static final class Match {
        private final java.util.regex.Matcher javaMatcher;
        private final com.google.re2j.Matcher linearMatcher;

        private Match(java.util.regex.Matcher javaMatcher, com.google.re2j.Matcher linearMatcher) {
            this.javaMatcher = javaMatcher;
            this.linearMatcher = linearMatcher;
        }

        boolean find() { return javaMatcher != null ? javaMatcher.find() : linearMatcher.find(); }
        int start() { return javaMatcher != null ? javaMatcher.start() : linearMatcher.start(); }
        int start(int group) { return javaMatcher != null ? javaMatcher.start(group) : linearMatcher.start(group); }
        int end() { return javaMatcher != null ? javaMatcher.end() : linearMatcher.end(); }
        int end(int group) { return javaMatcher != null ? javaMatcher.end(group) : linearMatcher.end(group); }
        String group() { return javaMatcher != null ? javaMatcher.group() : linearMatcher.group(); }
        String group(int group) { return javaMatcher != null ? javaMatcher.group(group) : linearMatcher.group(group); }
    }
}

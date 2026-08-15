/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.rules;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Offline matcher for the 2023-06-30 mainland-China administrative-division
 * snapshot. A compact Aho-Corasick automaton avoids a giant regular expression
 * and keeps matching time proportional to input length plus returned matches.
 */
final class ChineseAdministrativeDivisionLexicon {
    private static final String RESOURCE = "/rules/cn-administrative-divisions-2023.tsv.gz";
    private static final int NEARBY_HIERARCHY_CHARS = 16;
    private static final Set<String> LOCATION_CONTEXT = Set.of(
            "地址", "住址", "住所", "户籍", "籍贯", "所在地", "居住地", "现住", "出生地",
            "送达", "邮寄", "通信地址", "联系地址", "行政区划", "省份", "城市", "区县",
            "乡镇", "街道", "镇街", "苏木", "不动产", "房屋坐落", "项目地点");
    private static final Pattern TAB = Pattern.compile("\\t");

    private final List<Term> terms;
    private final int[] firstEdge;
    private final int[] edgeCount;
    private final char[] edgeCharacter;
    private final int[] edgeTarget;
    private final int[] failure;
    private final int[] outputTerm;
    private final Set<String> knownCodes;

    private ChineseAdministrativeDivisionLexicon() {
        Loaded loaded = loadTerms();
        terms = loaded.terms();
        knownCodes = loaded.knownCodes();

        List<MutableNode> nodes = new ArrayList<>();
        nodes.add(new MutableNode());
        for (int termIndex = 0; termIndex < terms.size(); termIndex++) {
            int node = 0;
            String value = terms.get(termIndex).name();
            for (int offset = 0; offset < value.length(); offset++) {
                char current = value.charAt(offset);
                Integer next = nodes.get(node).children.get(current);
                if (next == null) {
                    next = nodes.size();
                    nodes.get(node).children.put(current, next);
                    nodes.add(new MutableNode());
                }
                node = next;
            }
            nodes.get(node).term = termIndex;
        }

        firstEdge = new int[nodes.size()];
        edgeCount = new int[nodes.size()];
        outputTerm = new int[nodes.size()];
        Arrays.fill(outputTerm, -1);
        int totalEdges = nodes.stream().mapToInt(node -> node.children.size()).sum();
        edgeCharacter = new char[totalEdges];
        edgeTarget = new int[totalEdges];
        int edgeOffset = 0;
        for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
            MutableNode node = nodes.get(nodeIndex);
            firstEdge[nodeIndex] = edgeOffset;
            edgeCount[nodeIndex] = node.children.size();
            outputTerm[nodeIndex] = node.term;
            List<Map.Entry<Character, Integer>> edges = new ArrayList<>(node.children.entrySet());
            edges.sort(Map.Entry.comparingByKey());
            for (Map.Entry<Character, Integer> edge : edges) {
                edgeCharacter[edgeOffset] = edge.getKey();
                edgeTarget[edgeOffset] = edge.getValue();
                edgeOffset++;
            }
        }

        failure = new int[nodes.size()];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int offset = firstEdge[0]; offset < firstEdge[0] + edgeCount[0]; offset++) {
            queue.add(edgeTarget[offset]);
        }
        while (!queue.isEmpty()) {
            int parent = queue.removeFirst();
            int edgeEnd = firstEdge[parent] + edgeCount[parent];
            for (int offset = firstEdge[parent]; offset < edgeEnd; offset++) {
                char value = edgeCharacter[offset];
                int child = edgeTarget[offset];
                int fallback = failure[parent];
                int candidate = transition(fallback, value);
                while (candidate < 0 && fallback != 0) {
                    fallback = failure[fallback];
                    candidate = transition(fallback, value);
                }
                failure[child] = candidate < 0 || candidate == child ? 0 : candidate;
                queue.add(child);
            }
        }
    }

    static ChineseAdministrativeDivisionLexicon instance() {
        return Holder.INSTANCE;
    }

    static List<RuleDefinition> ruleDefinitions() {
        return List.of(
                placeholder("CN_ADMIN_PROVINCE", "中国大陆省级行政区", 79),
                placeholder("CN_ADMIN_PREFECTURE", "中国大陆地级市、州、地区或盟", 78),
                placeholder("CN_ADMIN_COUNTY", "中国大陆区县、县级市或旗", 77),
                placeholder("CN_ADMIN_TOWNSHIP", "中国大陆乡镇、街道或苏木", 76));
    }

    List<LexiconMatch> find(String text, Predicate<String> enabled, Predicate<String> whitelisted) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<RawMatch> raw = new ArrayList<>();
        int state = 0;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            int next = transition(state, value);
            while (next < 0 && state != 0) {
                state = failure[state];
                next = transition(state, value);
            }
            state = next < 0 ? 0 : next;
            int outputNode = state;
            while (outputNode != 0) {
                int termIndex = outputTerm[outputNode];
                if (termIndex >= 0) {
                    Term term = terms.get(termIndex);
                    int start = index - term.name().length() + 1;
                    raw.add(new RawMatch(term, start, index + 1));
                }
                outputNode = failure[outputNode];
            }
        }
        if (raw.isEmpty()) {
            return List.of();
        }
        raw.sort(Comparator.comparingInt(RawMatch::start)
                .thenComparing(Comparator.comparingInt(RawMatch::length).reversed()));
        List<RawMatch> higherLevel = raw.stream()
                .filter(match -> match.term().level() == Level.PROVINCE
                        || match.term().level() == Level.PREFECTURE)
                .toList();
        List<LexiconMatch> accepted = new ArrayList<>();
        for (RawMatch match : raw) {
            Term term = match.term();
            String ruleId = term.level().ruleId;
            String value = text.substring(match.start(), match.end());
            if (!enabled.test(ruleId) || whitelisted.test(value)) {
                continue;
            }
            boolean context = hasLocationContext(text, match.start(), match.end());
            boolean nearHierarchy = nearHigherLevel(match, higherLevel);
            boolean allow = switch (term.level()) {
                case PROVINCE, PREFECTURE -> true;
                case COUNTY -> !term.ambiguous() || context || nearHierarchy;
                case TOWNSHIP -> context || nearHierarchy;
            };
            if (allow) {
                accepted.add(new LexiconMatch(ruleId, term.level().label, term.level().priority,
                        match.start(), match.end()));
            }
        }
        return List.copyOf(accepted);
    }

    boolean isKnownCode(String value) {
        String normalized = value == null ? "" : value.replaceAll("[^0-9]", "");
        return knownCodes.contains(normalized);
    }

    int termCount() {
        return terms.size();
    }

    private int transition(int node, char value) {
        int low = firstEdge[node];
        int high = low + edgeCount[node] - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            char current = edgeCharacter[middle];
            if (current < value) {
                low = middle + 1;
            } else if (current > value) {
                high = middle - 1;
            } else {
                return edgeTarget[middle];
            }
        }
        return -1;
    }

    private static boolean nearHigherLevel(RawMatch target, List<RawMatch> higherLevel) {
        int left = target.start() - NEARBY_HIERARCHY_CHARS;
        int right = target.end() + NEARBY_HIERARCHY_CHARS;
        for (RawMatch higher : higherLevel) {
            if (higher.start() > right) {
                return false;
            }
            if (higher.end() >= left && higher != target) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLocationContext(String text, int start, int end) {
        int left = Math.max(0, start - 24);
        int right = Math.min(text.length(), end + 24);
        // Exclude the matched place name itself so a suffix such as “街道”
        // cannot serve as its own context and turn every township into a hit.
        String nearby = (text.substring(left, start) + "\u0000" + text.substring(end, right))
                .toLowerCase(Locale.ROOT);
        return LOCATION_CONTEXT.stream().anyMatch(nearby::contains);
    }

    private static Loaded loadTerms() {
        InputStream resource = ChineseAdministrativeDivisionLexicon.class.getResourceAsStream(RESOURCE);
        if (resource == null) {
            throw new IllegalStateException("缺少中国大陆行政区划离线词典：" + RESOURCE);
        }
        Map<String, MutableTerm> byName = new LinkedHashMap<>();
        Set<String> codes = new java.util.HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(resource), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#") || line.equals("level\tcode\tname")) {
                    continue;
                }
                String[] fields = TAB.split(line, -1);
                if (fields.length != 3) {
                    throw new IllegalStateException("行政区划词典字段数错误");
                }
                Level level = Level.valueOf(fields[0].toUpperCase(Locale.ROOT));
                String code = fields[1];
                String name = fields[2];
                codes.add(code);
                MutableTerm existing = byName.get(name);
                if (existing == null) {
                    byName.put(name, new MutableTerm(name, level, 1));
                } else {
                    existing.occurrences++;
                    if (level.priority > existing.level.priority) {
                        existing.level = level;
                    }
                }
            }
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException("无法加载中国大陆行政区划离线词典", ex);
        }
        List<Term> result = byName.values().stream()
                .map(value -> new Term(value.name, value.level, value.occurrences > 1))
                .sorted(Comparator.comparing(Term::name))
                .toList();
        return new Loaded(result, Set.copyOf(codes));
    }

    private static RuleDefinition placeholder(String id, String label, int priority) {
        return new RuleDefinition(id, label, "geography_location", priority,
                RedactionPattern.reviewed("(?!)", 0), 0, Validators::always);
    }

    record LexiconMatch(String ruleId, String label, int priority, int start, int end) {
    }

    private record Term(String name, Level level, boolean ambiguous) {
    }

    private record RawMatch(Term term, int start, int end) {
        int length() {
            return end - start;
        }
    }

    private record Loaded(List<Term> terms, Set<String> knownCodes) {
    }

    private enum Level {
        PROVINCE("CN_ADMIN_PROVINCE", "中国大陆省级行政区", 79),
        PREFECTURE("CN_ADMIN_PREFECTURE", "中国大陆地级市、州、地区或盟", 78),
        COUNTY("CN_ADMIN_COUNTY", "中国大陆区县、县级市或旗", 77),
        TOWNSHIP("CN_ADMIN_TOWNSHIP", "中国大陆乡镇、街道或苏木", 76);

        private final String ruleId;
        private final String label;
        private final int priority;

        Level(String ruleId, String label, int priority) {
            this.ruleId = ruleId;
            this.label = label;
            this.priority = priority;
        }
    }

    private static final class MutableTerm {
        private final String name;
        private Level level;
        private int occurrences;

        private MutableTerm(String name, Level level, int occurrences) {
            this.name = name;
            this.level = level;
            this.occurrences = occurrences;
        }
    }

    private static final class MutableNode {
        private final Map<Character, Integer> children = new HashMap<>();
        private int term = -1;
    }

    private static final class Holder {
        private static final ChineseAdministrativeDivisionLexicon INSTANCE =
                new ChineseAdministrativeDivisionLexicon();
    }
}

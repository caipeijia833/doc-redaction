/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.server;

import io.github.caipeijia833.docredaction.archive.ArchiveEntryAction;
import io.github.caipeijia833.docredaction.archive.ArchivePolicy;
import io.github.caipeijia833.docredaction.preview.PreviewResult;
import io.github.caipeijia833.docredaction.processor.LocalVlmEngine;
import io.github.caipeijia833.docredaction.rules.RuleDefinition;
import io.github.caipeijia833.docredaction.rules.RuleEngine;
import io.github.caipeijia833.docredaction.rules.CustomRuleSpec;
import io.github.caipeijia833.docredaction.rules.RuleTestMatch;
import io.github.caipeijia833.docredaction.rules.FilenameRedactor;
import io.github.caipeijia833.docredaction.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public final class RedactionServer implements AutoCloseable {
    private static final String TOKEN_HEADER = "X-Session-Token";
    private static final int MAX_HEADER_VALUE_LENGTH = 4_096;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final ExecutorService httpWorkers;
    private final JobService jobService;
    private final RuleEngine ruleEngine;
    private final String sessionToken;

    public RedactionServer(int preferredPort, JobService jobService, RuleEngine ruleEngine) throws IOException {
        this.server = bind(preferredPort);
        this.jobService = jobService;
        this.ruleEngine = ruleEngine;
        byte[] tokenBytes = new byte[32];
        new SecureRandom().nextBytes(tokenBytes);
        this.sessionToken = HexFormat.of().formatHex(tokenBytes);
        this.httpWorkers = Executors.newFixedThreadPool(Math.max(4,
                Math.min(16, Runtime.getRuntime().availableProcessors() * 2)),
                Thread.ofPlatform().name("local-http-", 0).factory());
        server.setExecutor(httpWorkers);
        server.createContext("/", new Router());
    }

    private static HttpServer bind(int preferredPort) throws IOException {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try {
            return HttpServer.create(new InetSocketAddress(loopback, preferredPort), 0);
        } catch (BindException ex) {
            return HttpServer.create(new InetSocketAddress(loopback, 0), 0);
        }
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private final class Router implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            applySecurityHeaders(exchange.getResponseHeaders());
            try {
                // Host validation prevents a public domain that resolves to loopback from
                // reading the bootstrap page and stealing its per-process session token.
                if (!allowedHost(exchange)) {
                    sendJson(exchange, 421, errorJson("请求主机无效，仅允许本机访问"));
                    return;
                }
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();
                if ("GET".equals(method) && "/".equals(path)) {
                    serveIndex(exchange);
                } else if ("GET".equals(method) && path.startsWith("/assets/")) {
                    serveAsset(exchange, path);
                } else if ("GET".equals(method) && "/api/health".equals(path)) {
                    sendJson(exchange, 200, "{\"status\":\"ok\",\"service\":\"doc-redaction-poc\"}");
                } else if (path.startsWith("/api/") && !authorized(exchange)) {
                    sendJson(exchange, 403, errorJson("会话验证失败，请刷新页面后重试"));
                } else if ("GET".equals(method) && "/api/config".equals(path)) {
                    serveConfig(exchange);
                } else if ("GET".equals(method) && "/api/rules".equals(path)) {
                    serveRules(exchange);
                } else if ("GET".equals(method) && "/api/rule-settings".equals(path)) {
                    serveRuleSettings(exchange);
                } else if ("GET".equals(method) && "/api/rules/export".equals(path)) {
                    exportCustomRules(exchange);
                } else if ("GET".equals(method) && "/api/rules/history".equals(path)) {
                    serveRuleHistory(exchange);
                } else if ("POST".equals(method) && "/api/rules/test".equals(path)) {
                    testCustomRule(exchange);
                } else if ("POST".equals(method) && "/api/rules/import".equals(path)) {
                    importCustomRules(exchange);
                } else if ("POST".equals(method) && path.matches("/api/rules/rollback/[0-9]+")) {
                    rollbackRules(exchange, Long.parseLong(lastSegment(path)));
                } else if ("PUT".equals(method) && path.matches("/api/rules/CUSTOM_[A-Z0-9_]+/definition")) {
                    updateCustomRule(exchange, path.split("/")[3]);
                } else if ("PUT".equals(method) && path.matches("/api/rules/[A-Z0-9_]+")) {
                    updateRule(exchange, lastSegment(path));
                } else if ("DELETE".equals(method) && path.matches("/api/rules/CUSTOM_[A-Z0-9_]+")) {
                    deleteCustomRule(exchange, lastSegment(path));
                } else if ("POST".equals(method) && "/api/rules/custom".equals(path)) {
                    addCustomRule(exchange);
                } else if ("PUT".equals(method) && "/api/lists/black".equals(path)) {
                    replaceRuleList(exchange, true);
                } else if ("PUT".equals(method) && "/api/lists/white".equals(path)) {
                    replaceRuleList(exchange, false);
                } else if ("POST".equals(method) && "/api/filename-preview".equals(path)) {
                    previewFilenames(exchange);
                } else if ("POST".equals(method) && "/api/capacity-estimate".equals(path)) {
                    estimateCapacity(exchange);
                } else if ("GET".equals(method) && "/api/jobs".equals(path)) {
                    serveJobs(exchange);
                } else if ("POST".equals(method) && "/api/jobs".equals(path)) {
                    acceptJob(exchange);
                } else if ("GET".equals(method) && path.matches("/api/jobs/[0-9a-fA-F-]+/inspection")) {
                    serveArchiveInspection(exchange, path.split("/")[3]);
                } else if ("POST".equals(method) && path.matches("/api/jobs/[0-9a-fA-F-]+/confirm")) {
                    confirmArchive(exchange, path.split("/")[3]);
                } else if ("GET".equals(method) && path.matches("/api/jobs/[0-9a-fA-F-]+/preview")) {
                    servePreview(exchange, path.split("/")[3]);
                } else if ("GET".equals(method) && path.matches("/api/jobs/[0-9a-fA-F-]+/media")) {
                    serveMedia(exchange, path.split("/")[3]);
                } else if ("GET".equals(method) && path.matches("/api/jobs/[0-9a-fA-F-]+/review")) {
                    serveReview(exchange, path.split("/")[3]);
                } else if ("POST".equals(method) && path.matches("/api/jobs/[0-9a-fA-F-]+/review/confirm")) {
                    confirmReview(exchange, path.split("/")[3]);
                } else if ("POST".equals(method) && path.matches("/api/jobs/[0-9a-fA-F-]+/restore")) {
                    restoreOriginal(exchange, path.split("/")[3]);
                } else if ("POST".equals(method) && path.matches("/api/jobs/[0-9a-fA-F-]+/cancel")) {
                    cancelJob(exchange, path.split("/")[3]);
                } else if ("POST".equals(method) && path.matches("/api/jobs/[0-9a-fA-F-]+/retry")) {
                    retryJob(exchange, path.split("/")[3]);
                } else if ("GET".equals(method) && path.matches(
                        "/api/projects/(?i:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/report")) {
                    serveProjectReport(exchange, path.split("/")[3]);
                } else if ("GET".equals(method) && path.matches(
                        "/api/projects/(?i:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/download")) {
                    serveProjectDownload(exchange, path.split("/")[3]);
                } else if ("POST".equals(method) && path.matches(
                        "/api/projects/(?i:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/restore")) {
                    restoreProjectOriginals(exchange, path.split("/")[3]);
                } else if ("DELETE".equals(method) && path.matches(
                        "/api/projects/(?i:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")) {
                    deleteProject(exchange, lastSegment(path));
                } else if ("DELETE".equals(method) && path.matches("/api/jobs/[0-9a-fA-F-]+")) {
                    deleteJob(exchange, lastSegment(path));
                } else if ("GET".equals(method) && path.matches("/api/jobs/[0-9a-fA-F-]+")) {
                    serveJob(exchange, lastSegment(path));
                } else if ("GET".equals(method) && path.matches("/api/jobs/[0-9a-fA-F-]+/download")) {
                    serveDownload(exchange, path.split("/")[3]);
                } else {
                    sendJson(exchange, 404, errorJson("资源不存在"));
                }
            } catch (IllegalArgumentException ex) {
                sendJson(exchange, 400, errorJson(safeMessage(ex)));
            } catch (IllegalStateException ex) {
                sendJson(exchange, 409, errorJson(safeMessage(ex)));
            } catch (IOException ex) {
                sendJson(exchange, 400, errorJson(safeMessage(ex)));
            } catch (Throwable ex) {
                sendJson(exchange, 500, errorJson("本地服务处理失败"));
            } finally {
                exchange.close();
            }
        }
    }

    private boolean allowedHost(HttpExchange exchange) {
        List<String> values = exchange.getRequestHeaders().get("Host");
        if (values == null || values.size() != 1) {
            return false;
        }
        String authority = values.getFirst().trim().toLowerCase(Locale.ROOT);
        if (authority.isEmpty() || authority.length() > 255 || authority.contains("@")) {
            return false;
        }
        String portSuffix = ":" + port();
        return authority.equals("localhost") || authority.equals("localhost" + portSuffix)
                || authority.equals("127.0.0.1") || authority.equals("127.0.0.1" + portSuffix)
                || authority.equals("[::1]") || authority.equals("[::1]" + portSuffix);
    }

    private void serveIndex(HttpExchange exchange) throws IOException {
        String html = readResource("/web/index.html")
                .replace("__SESSION_TOKEN__", sessionToken);
        exchange.getResponseHeaders().add("Set-Cookie", "redaction_session=" + sessionToken
                + "; HttpOnly; SameSite=Strict; Path=/");
        sendBytes(exchange, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
    }

    private void serveAsset(HttpExchange exchange, String path) throws IOException {
        if (!path.matches("/assets/[a-zA-Z0-9._-]+")) {
            sendJson(exchange, 404, errorJson("资源不存在"));
            return;
        }
        String name = path.substring("/assets/".length());
        String type = name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".js") ? "text/javascript; charset=utf-8"
                : "application/octet-stream";
        try (InputStream input = RedactionServer.class.getResourceAsStream("/web/" + name)) {
            if (input == null) {
                sendJson(exchange, 404, errorJson("资源不存在"));
                return;
            }
            sendBytes(exchange, 200, type, input.readAllBytes());
        }
    }

    private void serveConfig(HttpExchange exchange) throws IOException {
        LocalVlmEngine.Capability vlm = LocalVlmEngine.inspect();
        String extensions = jobService.registry().supportedExtensions().stream()
                .map(JsonUtil::quote).collect(Collectors.joining(",", "[", "]"));
        String json = "{" +
                "\"maxUploadBytes\":" + JobService.MAX_UPLOAD_BYTES + ',' +
                "\"maxQueuedJobs\":" + JobService.MAX_QUEUED_JOBS + ',' +
                "\"maxDataBytes\":" + jobService.storageQuota().maxDataBytes() + ',' +
                "\"minFreeBytes\":" + jobService.storageQuota().minFreeBytes() + ',' +
                "\"currentDataBytes\":" + jobService.storageQuota().currentDataBytes() + ',' +
                "\"workerMaxHeap\":" + JsonUtil.quote(jobService.workerProcessRunner().maximumHeap()) + ',' +
                "\"workerTimeoutMinutes\":" + jobService.workerProcessRunner().timeoutMinutes() + ',' +
                "\"environmentProfile\":" + JsonUtil.quote(System.getProperty("docredaction.environment.profile", "development")) + ',' +
                "\"ocrAvailable\":" + jobService.ocrCapability().available() + ',' +
                "\"ocrLanguages\":" + JsonUtil.quote(jobService.ocrCapability().languages()) + ',' +
                "\"ocrVersion\":" + JsonUtil.quote(jobService.ocrCapability().version()) + ',' +
                "\"ocrMessage\":" + JsonUtil.quote(jobService.ocrCapability().message()) + ',' +
                "\"mediaAvailable\":" + jobService.mediaCapability().available() + ',' +
                "\"ffmpegVersion\":" + JsonUtil.quote(jobService.mediaCapability().ffmpegVersion()) + ',' +
                "\"whisperVersion\":" + JsonUtil.quote(jobService.mediaCapability().whisperVersion()) + ',' +
                "\"openCvVersion\":" + JsonUtil.quote(jobService.mediaCapability().openCvVersion()) + ',' +
                "\"faceModelAvailable\":" + jobService.mediaCapability().faceModelAvailable() + ',' +
                "\"plateModelAvailable\":" + jobService.mediaCapability().plateModelAvailable() + ',' +
                "\"mediaProblems\":" + JsonUtil.stringArray(jobService.mediaCapability().problems()) + ',' +
                "\"vlmAvailable\":" + vlm.available() + ',' +
                "\"vlmMode\":" + JsonUtil.quote(vlm.mode()) + ',' +
                "\"vlmModelId\":" + JsonUtil.quote(vlm.modelId()) + ',' +
                "\"vlmRuntimeVersion\":" + JsonUtil.quote(vlm.runtimeVersion()) + ',' +
                "\"vlmMessage\":" + JsonUtil.quote(vlm.message()) + ',' +
                "\"supportedExtensions\":" + extensions + ',' +
                "\"archiveExtensions\":[\"zip\",\"7z\",\"tar\",\"tar.gz\",\"tgz\"]," +
                "\"recognizedUnsupportedExtensions\":[\"rar\"]," +
                "\"ruleCount\":" + ruleEngine.rules().size() + ',' +
                "\"ruleVersion\":" + ruleEngine.version() + ',' +
                "\"offline\":true," +
                "\"bindAddress\":\"127.0.0.1\"" +
                "}";
        sendJson(exchange, 200, json);
    }

    private void serveRules(HttpExchange exchange) throws IOException {
        String json = ruleEngine.rules().stream().map(this::ruleJson)
                .collect(Collectors.joining(",", "[", "]"));
        sendJson(exchange, 200, json);
    }

    private String ruleJson(RuleDefinition rule) {
        return "{" +
                "\"id\":" + JsonUtil.quote(rule.id()) + ',' +
                "\"label\":" + JsonUtil.quote(rule.label()) + ',' +
                "\"category\":" + JsonUtil.quote(rule.category()) + ',' +
                "\"priority\":" + rule.priority() + ',' +
                "\"enabled\":" + ruleEngine.isEnabled(rule.id()) + ',' +
                "\"source\":" + JsonUtil.quote(rule.id().startsWith("CUSTOM_") ? "custom" : "built_in") + ',' +
                "\"pattern\":" + JsonUtil.quote(rule.pattern().pattern()) +
                "}";
    }

    private void serveRuleSettings(HttpExchange exchange) throws IOException {
        String json = "{" +
                "\"version\":" + ruleEngine.version() + ',' +
                "\"blacklist\":" + JsonUtil.stringArray(ruleEngine.blacklist()) + ',' +
                "\"whitelist\":" + JsonUtil.stringArray(ruleEngine.whitelist()) +
                "}";
        sendJson(exchange, 200, json);
    }

    private void updateRule(HttpExchange exchange, String id) throws IOException {
        String enabled = exchange.getRequestHeaders().getFirst("X-Rule-Enabled");
        if (enabled == null || !(enabled.equalsIgnoreCase("true") || enabled.equalsIgnoreCase("false"))) {
            throw new IllegalArgumentException("缺少有效的X-Rule-Enabled请求头");
        }
        ruleEngine.setEnabled(id, Boolean.parseBoolean(enabled));
        sendJson(exchange, 200, "{\"updated\":true,\"version\":" + ruleEngine.version() + "}");
    }

    private void addCustomRule(HttpExchange exchange) throws IOException {
        String id = decodedHeader(exchange, "X-Rule-Id", "");
        String label = decodedHeader(exchange, "X-Rule-Label", "");
        String category = decodedHeader(exchange, "X-Rule-Category", "custom");
        String regex = readSmallText(exchange, 16_384).trim();
        int priority = headerInteger(exchange, "X-Rule-Priority", 50, 1, 999);
        RuleDefinition rule = ruleEngine.addCustomRule(id, label, category, priority, regex);
        sendJson(exchange, 201, ruleJson(rule));
    }

    private void deleteCustomRule(HttpExchange exchange, String id) throws IOException {
        ruleEngine.removeCustomRule(id);
        sendJson(exchange, 200, "{\"deleted\":true,\"version\":" + ruleEngine.version() + "}");
    }

    private void updateCustomRule(HttpExchange exchange, String id) throws IOException {
        String label = decodedHeader(exchange, "X-Rule-Label", "");
        String category = decodedHeader(exchange, "X-Rule-Category", "custom");
        String regex = readSmallText(exchange, 16_384).trim();
        int priority = headerInteger(exchange, "X-Rule-Priority", 50, 1, 999);
        RuleDefinition rule = ruleEngine.updateCustomRule(id, label, category, priority, regex);
        sendJson(exchange, 200, ruleJson(rule));
    }

    private void testCustomRule(HttpExchange exchange) throws IOException {
        String regex = decodedHeader(exchange, "X-Rule-Regex", "");
        String sample = readSmallText(exchange, 64 * 1024);
        List<RuleTestMatch> matches = ruleEngine.testCustomRule(regex, sample);
        String json = matches.stream().map(match -> "{" +
                        "\"start\":" + match.start() + ',' +
                        "\"end\":" + match.end() + ',' +
                        "\"value\":" + JsonUtil.quote(match.value()) + ',' +
                        "\"context\":" + JsonUtil.quote(match.context()) +
                        "}")
                .collect(Collectors.joining(",", "{\"count\":" + matches.size() + ",\"matches\":[", "]}"));
        sendJson(exchange, 200, json);
    }

    private void exportCustomRules(HttpExchange exchange) throws IOException {
        String rules = ruleEngine.customRuleSpecs().stream().map(specification -> "{" +
                        "\"id\":" + JsonUtil.quote(specification.id()) + ',' +
                        "\"label\":" + JsonUtil.quote(specification.label()) + ',' +
                        "\"category\":" + JsonUtil.quote(specification.category()) + ',' +
                        "\"priority\":" + specification.priority() + ',' +
                        "\"regex\":" + JsonUtil.quote(specification.regex()) +
                        "}")
                .collect(Collectors.joining(",", "[", "]"));
        String json = "{\"format\":\"doc-redaction-custom-rules/v1\",\"ruleVersion\":"
                + ruleEngine.version() + ",\"customRules\":" + rules + "}";
        exchange.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=doc-redaction-custom-rules.json");
        sendBytes(exchange, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private void importCustomRules(HttpExchange exchange) throws IOException {
        String body = readSmallText(exchange, 1024 * 1024);
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException ex) {
            throw new IllegalArgumentException("规则导入文件不是有效JSON");
        }
        if (root == null || !"doc-redaction-custom-rules/v1".equals(root.path("format").asText())
                || !root.path("customRules").isArray()) {
            throw new IllegalArgumentException("规则导入文件格式或版本不受支持");
        }
        List<CustomRuleSpec> rules = new ArrayList<>();
        for (JsonNode item : root.path("customRules")) {
            if (rules.size() >= 100) {
                throw new IllegalArgumentException("自定义规则最多100条");
            }
            rules.add(new CustomRuleSpec(item.path("id").asText(), item.path("label").asText(),
                    item.path("category").asText("custom"), item.path("priority").asInt(50),
                    item.path("regex").asText()));
        }
        String mode = exchange.getRequestHeaders().getFirst("X-Rule-Import-Mode");
        boolean replace = "replace".equalsIgnoreCase(mode);
        ruleEngine.importCustomRules(rules, replace);
        sendJson(exchange, 200, "{\"imported\":" + rules.size() + ",\"replace\":" + replace
                + ",\"version\":" + ruleEngine.version() + "}");
    }

    private void serveRuleHistory(HttpExchange exchange) throws IOException {
        String versions = ruleEngine.historyVersions().stream().map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
        sendJson(exchange, 200, "{\"current\":" + ruleEngine.version()
                + ",\"versions\":" + versions + "}");
    }

    private void rollbackRules(HttpExchange exchange, long targetVersion) throws IOException {
        ruleEngine.rollback(targetVersion);
        sendJson(exchange, 200, "{\"rolledBackFrom\":" + targetVersion
                + ",\"version\":" + ruleEngine.version() + "}");
    }

    private void replaceRuleList(HttpExchange exchange, boolean black) throws IOException {
        String content = readSmallText(exchange, 256 * 1024);
        List<String> values = content.lines().map(String::trim).filter(value -> !value.isBlank()).toList();
        if (black) {
            ruleEngine.replaceBlacklist(values);
        } else {
            ruleEngine.replaceWhitelist(values);
        }
        sendJson(exchange, 200, "{\"updated\":true,\"version\":" + ruleEngine.version()
                + ",\"count\":" + values.size() + "}");
    }

    private void serveJobs(HttpExchange exchange) throws IOException {
        String json = jobService.store().list().stream().map(JobRecord::toJson)
                .collect(Collectors.joining(",", "[", "]"));
        sendJson(exchange, 200, json);
    }

    private void serveJob(HttpExchange exchange, String id) throws IOException {
        JobRecord job = jobService.store().get(id).orElse(null);
        if (job == null) {
            sendJson(exchange, 404, errorJson("任务不存在"));
            return;
        }
        sendJson(exchange, 200, job.toJson());
    }

    private void acceptJob(HttpExchange exchange) throws IOException {
        long contentLength = parseContentLength(exchange.getRequestHeaders());
        if (contentLength > JobService.MAX_UPLOAD_BYTES) {
            sendJson(exchange, 413, errorJson("文件超过1GB上限"));
            return;
        }
        String originalName = decodedHeader(exchange, "X-Filename", "");
        String projectName = decodedHeader(exchange, "X-Project-Name", "未命名项目");
        String projectId = decodedHeader(exchange, "X-Project-Id", "");
        String processingMode = decodedHeader(exchange, "X-Processing-Mode", "external_irreversible");
        String passphrase = decodedHeader(exchange, "X-Vault-Passphrase", "");
        boolean requireReview = Boolean.parseBoolean(exchange.getRequestHeaders().getFirst("X-Require-Review"));
        List<String> selectedRuleCategories = commaSeparatedHeader(exchange, "X-Rule-Categories");
        if (originalName.isBlank()) {
            sendJson(exchange, 400, errorJson("缺少文件名"));
            return;
        }
        JobRecord job = jobService.accept(projectName, originalName, exchange.getRequestBody(),
                processingMode, passphrase.isEmpty() ? null : passphrase.toCharArray(), requireReview,
                projectId, contentLength, selectedRuleCategories);
        sendJson(exchange, 202, job.toJson());
    }

    private void previewFilenames(HttpExchange exchange) throws IOException {
        JsonNode root;
        try {
            root = JSON.readTree(readSmallText(exchange, 256 * 1024));
        } catch (IOException ex) {
            throw new IllegalArgumentException("文件名预览请求不是有效JSON");
        }
        if (root == null || !root.path("names").isArray()) {
            throw new IllegalArgumentException("文件名预览请求缺少names数组");
        }
        List<String> names = new ArrayList<>();
        for (JsonNode item : root.path("names")) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("文件名必须是文本");
            }
            names.add(item.asText());
        }
        List<String> categories = new ArrayList<>();
        if (root.path("categories").isArray()) {
            for (JsonNode item : root.path("categories")) {
                if (item.isTextual()) {
                    categories.add(item.asText());
                }
            }
        }
        String projectId = root.path("projectId").asText("");
        String items = jobService.previewFilenames(projectId, names, categories).stream()
                .map(item -> "{" +
                        "\"originalName\":" + JsonUtil.quote(item.originalName()) + ',' +
                        "\"redactedName\":" + JsonUtil.quote(item.redactedName()) + ',' +
                        "\"changed\":" + item.changed() +
                        "}")
                .collect(Collectors.joining(",", "[", "]"));
        sendJson(exchange, 200, "{\"items\":" + items + "}");
    }

    private void estimateCapacity(HttpExchange exchange) throws IOException {
        JsonNode root;
        try {
            root = JSON.readTree(readSmallText(exchange, 1024 * 1024));
        } catch (IOException ex) {
            throw new IllegalArgumentException("容量评估请求不是有效JSON");
        }
        if (root == null || !root.path("files").isArray()) {
            throw new IllegalArgumentException("容量评估请求缺少files数组");
        }
        List<JobService.CapacityFile> files = new ArrayList<>();
        for (JsonNode item : root.path("files")) {
            if (!item.path("name").isTextual() || !item.path("size").canConvertToLong()) {
                throw new IllegalArgumentException("容量评估文件参数无效");
            }
            files.add(new JobService.CapacityFile(item.path("name").asText(), item.path("size").asLong()));
            if (files.size() > JobService.MAX_QUEUED_JOBS) {
                throw new IllegalArgumentException("容量评估文件数量超过安全上限");
            }
        }
        JobService.CapacityEstimate estimate = jobService.estimateCapacity(files,
                root.path("processingMode").asText("external_irreversible"));
        sendJson(exchange, 200, "{" +
                "\"selectedBytes\":" + estimate.selectedBytes() + ',' +
                "\"persistentBytes\":" + estimate.persistentBytes() + ',' +
                "\"peakWorkspaceBytes\":" + estimate.peakWorkspaceBytes() + ',' +
                "\"requiredFreeBytes\":" + estimate.requiredFreeBytes() + ',' +
                "\"usableDiskBytes\":" + estimate.usableDiskBytes() + ',' +
                "\"currentDataBytes\":" + estimate.currentDataBytes() + ',' +
                "\"maxDataBytes\":" + estimate.maxDataBytes() + ',' +
                "\"minFreeBytes\":" + estimate.minFreeBytes() + ',' +
                "\"archiveEstimateIncomplete\":" + estimate.archiveEstimateIncomplete() + ',' +
                "\"dataQuotaSufficient\":" + estimate.dataQuotaSufficient() + ',' +
                "\"diskSufficient\":" + estimate.diskSufficient() + ',' +
                "\"sufficient\":" + estimate.sufficient() +
                "}");
    }

    private void cancelJob(HttpExchange exchange, String id) throws IOException {
        sendJson(exchange, 200, jobService.cancel(id).toJson());
    }

    private void retryJob(HttpExchange exchange, String id) throws IOException {
        sendJson(exchange, 202, jobService.retry(id).toJson());
    }

    private void deleteJob(HttpExchange exchange, String id) throws IOException {
        jobService.delete(id);
        sendJson(exchange, 200, "{\"deleted\":true}");
    }

    private void serveProjectReport(HttpExchange exchange, String projectId) throws IOException {
        byte[] report = projectReportJson(projectId).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=redaction-report-" + projectId.substring(0, 8) + ".json");
        sendBytes(exchange, 200, "application/json; charset=utf-8", report);
    }

    private void serveProjectDownload(HttpExchange exchange, String projectId) throws IOException {
        List<JobRecord> jobs = jobService.projectJobs(projectId);
        if (jobs.stream().anyMatch(job -> !isTerminal(job.status()))) {
            throw new IllegalStateException("项目仍有待处理或待确认任务，不能生成最终结果包");
        }
        List<JobRecord> completed = jobs.stream()
                .filter(job -> job.status() == JobStatus.COMPLETED && job.outputPath() != null
                        && Files.isRegularFile(job.outputPath()))
                .toList();
        if (completed.isEmpty()) {
            throw new IllegalStateException("项目没有可下载的脱敏结果");
        }
        String downloadName = "redacted-results-" + projectId.substring(0, 8) + ".zip";
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/zip");
        headers.set("Content-Disposition", "attachment; filename=" + downloadName);
        exchange.sendResponseHeaders(200, 0);
        Set<String> usedNames = new HashSet<>();
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(exchange.getResponseBody())) {
            output.setUseZip64(org.apache.commons.compress.archivers.zip.Zip64Mode.AsNeeded);
            byte[] report = projectReportJson(projectId).getBytes(StandardCharsets.UTF_8);
            ZipArchiveEntry reportEntry = new ZipArchiveEntry("_redaction_report.json");
            reportEntry.setSize(report.length);
            output.putArchiveEntry(reportEntry);
            output.write(report);
            output.closeArchiveEntry();
            usedNames.add("_redaction_report.json");
            for (JobRecord job : completed) {
                String entryName = FilenameRedactor.allocateUnique(job.redactedName(), usedNames);
                ZipArchiveEntry entry = new ZipArchiveEntry(entryName);
                entry.setSize(Files.size(job.outputPath()));
                output.putArchiveEntry(entry);
                Files.copy(job.outputPath(), output);
                output.closeArchiveEntry();
            }
        }
    }

    private void restoreProjectOriginals(HttpExchange exchange, String projectId) throws IOException {
        if (!"RESTORE_SELECTED_ORIGINALS".equals(
                exchange.getRequestHeaders().getFirst("X-Restore-Confirmation"))) {
            throw new IllegalArgumentException("批量还原需要明确二次确认");
        }
        String passphrase = decodedHeader(exchange, "X-Vault-Passphrase", "");
        if (passphrase.isEmpty()) {
            throw new IllegalArgumentException("请输入还原口令");
        }
        JsonNode root;
        try {
            root = JSON.readTree(readSmallText(exchange, 1024 * 1024));
        } catch (IOException ex) {
            throw new IllegalArgumentException("批量还原请求不是有效JSON");
        }
        if (root == null || !root.path("jobIds").isArray() || root.path("jobIds").isEmpty()) {
            throw new IllegalArgumentException("请选择至少一个可还原文件");
        }
        List<String> jobIds = new ArrayList<>();
        for (JsonNode item : root.path("jobIds")) {
            if (!item.isTextual() || jobIds.size() >= JobService.MAX_QUEUED_JOBS) {
                throw new IllegalArgumentException("批量还原任务清单无效");
            }
            jobIds.add(item.asText());
        }
        JobService.PreparedRestoreArchive prepared = jobService.prepareProjectRestore(
                projectId, jobIds, passphrase.toCharArray());
        try {
            String encoded = URLEncoder.encode(prepared.downloadName(), StandardCharsets.UTF_8)
                    .replace("+", "%20");
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "application/zip");
            headers.set("Content-Disposition", "attachment; filename=restored-originals.zip; filename*=UTF-8''"
                    + encoded);
            exchange.sendResponseHeaders(200, Files.size(prepared.path()));
            try (InputStream input = Files.newInputStream(prepared.path())) {
                input.transferTo(exchange.getResponseBody());
            }
        } finally {
            try {
                prepared.close();
            } catch (IOException ignored) {
                // The response is already complete; orphan cleanup also runs on the next local maintenance cycle.
            }
        }
    }

    private String projectReportJson(String projectId) {
        List<JobRecord> jobs = jobService.projectJobs(projectId);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (JobRecord job : jobs) {
            job.counts().forEach((key, value) -> counts.merge(key, value, Integer::sum));
        }
        long completed = jobs.stream().filter(job -> job.status() == JobStatus.COMPLETED).count();
        long failed = jobs.stream().filter(job -> job.status() == JobStatus.FAILED
                || job.status() == JobStatus.INTERRUPTED).count();
        long cancelled = jobs.stream().filter(job -> job.status() == JobStatus.CANCELLED).count();
        long pending = jobs.size() - completed - failed - cancelled;
        String items = jobs.stream().map(JobRecord::toJson).collect(Collectors.joining(",", "[", "]"));
        return "{" +
                "\"schema\":\"doc-redaction-project-report/v1\"," +
                "\"generatedAt\":" + JsonUtil.quote(Instant.now().toString()) + ',' +
                "\"projectId\":" + JsonUtil.quote(projectId) + ',' +
                "\"projectName\":" + JsonUtil.quote(jobs.getFirst().projectName()) + ',' +
                "\"fileCount\":" + jobs.size() + ',' +
                "\"completedCount\":" + completed + ',' +
                "\"failedCount\":" + failed + ',' +
                "\"cancelledCount\":" + cancelled + ',' +
                "\"pendingCount\":" + pending + ',' +
                "\"selectedBytes\":" + jobs.stream().mapToLong(JobRecord::size).sum() + ',' +
                "\"totalMatches\":" + jobs.stream().mapToInt(JobRecord::totalMatches).sum() + ',' +
                "\"containsConfirmedUnprocessedFiles\":"
                        + jobs.stream().anyMatch(JobRecord::includeUnprocessed) + ',' +
                "\"counts\":" + JsonUtil.stringMap(counts) + ',' +
                "\"jobs\":" + items +
                "}";
    }

    private static boolean isTerminal(JobStatus status) {
        return status == JobStatus.COMPLETED || status == JobStatus.FAILED
                || status == JobStatus.CANCELLED || status == JobStatus.INTERRUPTED;
    }

    private void deleteProject(HttpExchange exchange, String projectId) throws IOException {
        int count = jobService.deleteProject(projectId);
        sendJson(exchange, 200, "{\"deleted\":true,\"jobCount\":" + count + "}");
    }

    private void serveArchiveInspection(HttpExchange exchange, String id) throws IOException {
        sendJson(exchange, 200, jobService.archiveInspection(id).toJson());
    }

    private void confirmArchive(HttpExchange exchange, String id) throws IOException {
        String body = readSmallText(exchange, 1024 * 1024);
        if (!body.isBlank()) {
            JsonNode root;
            try {
                root = JSON.readTree(body);
            } catch (IOException ex) {
                throw new IllegalArgumentException("压缩包处理决策不是有效JSON");
            }
            if (root == null || !root.path("decisions").isArray()) {
                throw new IllegalArgumentException("压缩包处理决策缺少decisions数组");
            }
            Map<Integer, ArchiveEntryAction> decisions = new LinkedHashMap<>();
            for (JsonNode item : root.path("decisions")) {
                int index = item.path("index").asInt(-1);
                ArchiveEntryAction action;
                try {
                    action = ArchiveEntryAction.valueOf(item.path("action").asText(""));
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException("压缩包条目处理动作无效");
                }
                if (index <= 0 || decisions.putIfAbsent(index, action) != null) {
                    throw new IllegalArgumentException("压缩包条目索引无效或重复");
                }
                if (decisions.size() > ArchivePolicy.MAX_ENTRIES) {
                    throw new IllegalArgumentException("压缩包处理决策超过安全上限");
                }
            }
            String confirmation = root.path("riskConfirmation").asText("");
            JobRecord job = jobService.confirmArchive(id, decisions, confirmation);
            sendJson(exchange, 202, job.toJson());
            return;
        }
        boolean includeUnprocessed = Boolean.parseBoolean(
                exchange.getRequestHeaders().getFirst("X-Include-Unprocessed"));
        String confirmation = exchange.getRequestHeaders().getFirst("X-Risk-Confirmation");
        JobRecord job = jobService.confirmArchive(id, includeUnprocessed, confirmation);
        sendJson(exchange, 202, job.toJson());
    }

    private void servePreview(HttpExchange exchange, String id) throws Exception {
        JobRecord job = jobService.store().get(id).orElse(null);
        if (job == null) {
            sendJson(exchange, 404, errorJson("任务不存在"));
            return;
        }
        Path previewFile = job.status() == JobStatus.COMPLETED ? job.outputPath()
                : job.status() == JobStatus.AWAITING_REVIEW ? job.inputPath() : null;
        if (previewFile == null || !Files.isRegularFile(previewFile)) {
            sendJson(exchange, 409, errorJson("当前状态没有可用的本地预览"));
            return;
        }
        if (io.github.caipeijia833.docredaction.processor.MediaProbe.isSupportedFileName(job.originalName())) {
            boolean video = isVideoName(job.originalName());
            int timeMillis = queryInteger(exchange, "timeMs", 0, 0, 12 * 60 * 60 * 1_000);
            String mediaSource = "/api/jobs/" + id + "/media"
                    + (timeMillis > 0 ? "#t=" + String.format(Locale.ROOT, "%.3f", timeMillis / 1_000.0d) : "");
            String element = video
                    ? "<video controls preload=\"metadata\" src=\"" + mediaSource + "\"></video>"
                    : "<audio controls preload=\"metadata\" src=\"" + mediaSource + "\"></audio>";
            String html = "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<title>本地音视频预览 / Local Media Preview</title><style>html,body{height:100%;margin:0;background:#111;color:#eee}"
                    + "body{display:grid;place-items:center;font-family:system-ui,sans-serif}"
                    + "main{width:min(96vw,1200px);text-align:center}video{width:100%;max-height:86vh}"
                    + "audio{width:min(92vw,760px)}p{font-size:13px;color:#aaa}</style></head><body><main>"
                    + element + "<p>文件仅从本机回环地址流式读取，不上传网络。 / Streamed only over the local loopback interface.</p></main></body></html>";
            sendBytes(exchange, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
            return;
        }
        int page = queryInteger(exchange, "page", 0, 0, 100_000);
        PreviewResult preview = jobService.workerProcessRunner().preview(job, previewFile, page);
        sendBytes(exchange, 200, preview.contentType(), preview.bytes());
    }

    private void serveMedia(HttpExchange exchange, String id) throws IOException {
        JobRecord job = jobService.store().get(id).orElse(null);
        if (job == null || !io.github.caipeijia833.docredaction.processor.MediaProbe.isSupportedFileName(job.originalName())) {
            sendJson(exchange, 404, errorJson("音视频任务不存在"));
            return;
        }
        Path file = job.status() == JobStatus.COMPLETED ? job.outputPath()
                : job.status() == JobStatus.AWAITING_REVIEW ? job.inputPath() : null;
        if (file == null || !Files.isRegularFile(file)) {
            sendJson(exchange, 409, errorJson("当前状态没有可用的音视频预览"));
            return;
        }
        long size = Files.size(file);
        long start = 0L;
        long end = Math.max(0L, size - 1L);
        int status = 200;
        String range = exchange.getRequestHeaders().getFirst("Range");
        if (range != null && !range.isBlank()) {
            if (!range.matches("bytes=\\d*-\\d*") || range.contains(",")) {
                exchange.getResponseHeaders().set("Content-Range", "bytes */" + size);
                exchange.sendResponseHeaders(416, -1);
                return;
            }
            String value = range.substring("bytes=".length());
            int dash = value.indexOf('-');
            String left = value.substring(0, dash);
            String right = value.substring(dash + 1);
            try {
                if (left.isBlank()) {
                    long suffix = Long.parseLong(right);
                    if (suffix <= 0L) throw new NumberFormatException();
                    start = Math.max(0L, size - suffix);
                } else {
                    start = Long.parseLong(left);
                }
                if (!right.isBlank() && !left.isBlank()) {
                    end = Math.min(end, Long.parseLong(right));
                }
            } catch (NumberFormatException ex) {
                exchange.getResponseHeaders().set("Content-Range", "bytes */" + size);
                exchange.sendResponseHeaders(416, -1);
                return;
            }
            if (start < 0L || start >= size || end < start) {
                exchange.getResponseHeaders().set("Content-Range", "bytes */" + size);
                exchange.sendResponseHeaders(416, -1);
                return;
            }
            status = 206;
        }
        long length = size == 0L ? 0L : end - start + 1L;
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", mediaContentType(job.originalName()));
        headers.set("Accept-Ranges", "bytes");
        if (status == 206) {
            headers.set("Content-Range", "bytes " + start + '-' + end + '/' + size);
        }
        exchange.sendResponseHeaders(status, length);
        try (var channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            long remaining = length;
            while (remaining > 0L) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), remaining));
                int read = channel.read(buffer);
                if (read < 0) break;
                exchange.getResponseBody().write(buffer.array(), 0, read);
                remaining -= read;
            }
        }
    }

    private static boolean isVideoName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".mkv");
    }

    private static String mediaContentType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".flac")) return "audio/flac";
        return "application/octet-stream";
    }

    private void serveReview(HttpExchange exchange, String id) throws IOException {
        sendJson(exchange, 200, jobService.reviewInspection(id).toJson());
    }

    private void confirmReview(HttpExchange exchange, String id) throws IOException {
        String content = readSmallText(exchange, 1024 * 1024);
        List<String> ignoredValues = content.lines().map(String::trim).filter(value -> !value.isBlank()).toList();
        JobRecord job = jobService.confirmReview(id, ignoredValues);
        sendJson(exchange, 202, job.toJson());
    }

    private void restoreOriginal(HttpExchange exchange, String id) throws IOException {
        JobRecord job = jobService.store().get(id).orElse(null);
        if (job == null) {
            sendJson(exchange, 404, errorJson("任务不存在"));
            return;
        }
        String passphrase = decodedHeader(exchange, "X-Vault-Passphrase", "");
        if (passphrase.isEmpty()) {
            throw new IllegalArgumentException("请输入还原口令");
        }
        Path restored = jobService.restore(id, passphrase.toCharArray());
        String encoded = URLEncoder.encode(job.originalName(), StandardCharsets.UTF_8).replace("+", "%20");
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/octet-stream");
        headers.set("Content-Disposition", "attachment; filename=\"restored-original\"; filename*=UTF-8''" + encoded);
        try {
            exchange.sendResponseHeaders(200, Files.size(restored));
            try (var stream = Files.newInputStream(restored)) {
                stream.transferTo(exchange.getResponseBody());
            }
        } finally {
            Files.deleteIfExists(restored);
        }
    }

    private void serveDownload(HttpExchange exchange, String id) throws IOException {
        JobRecord job = jobService.store().get(id).orElse(null);
        if (job == null) {
            sendJson(exchange, 404, errorJson("任务不存在"));
            return;
        }
        Path output = job.outputPath();
        if (job.status() != JobStatus.COMPLETED || output == null || !Files.isRegularFile(output)) {
            sendJson(exchange, 409, errorJson("脱敏结果尚未生成"));
            return;
        }
        String filename = output.getFileName().toString();
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/octet-stream");
        headers.set("Content-Disposition", "attachment; filename=\"redacted-output\"; filename*=UTF-8''" + encoded);
        exchange.sendResponseHeaders(200, Files.size(output));
        try (var stream = Files.newInputStream(output)) {
            stream.transferTo(exchange.getResponseBody());
        }
    }

    private boolean authorized(HttpExchange exchange) {
        String token = exchange.getRequestHeaders().getFirst(TOKEN_HEADER);
        if (sessionToken.equals(token)) {
            return true;
        }
        List<String> cookieHeaders = exchange.getRequestHeaders().getOrDefault("Cookie", List.of());
        return cookieHeaders.stream().flatMap(value -> List.of(value.split(";")).stream())
                .map(String::trim)
                .anyMatch(value -> value.equals("redaction_session=" + sessionToken));
    }

    private static long parseContentLength(Headers headers) {
        String value = headers.getFirst("Content-Length");
        if (value == null || value.isBlank()) {
            return -1;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Content-Length无效");
        }
    }

    private static String decodedHeader(HttpExchange exchange, String name, String fallback) {
        String value = exchange.getRequestHeaders().getFirst(name);
        if (value == null) {
            return fallback;
        }
        if (value.length() > MAX_HEADER_VALUE_LENGTH) {
            throw new IllegalArgumentException(name + "过长");
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(name + "编码无效");
        }
    }

    private static List<String> commaSeparatedHeader(HttpExchange exchange, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        if (value.length() > MAX_HEADER_VALUE_LENGTH) {
            throw new IllegalArgumentException(name + "过长");
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim).filter(item -> !item.isBlank()).distinct().toList();
    }

    private static String lastSegment(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static int queryInteger(HttpExchange exchange, String name, int fallback, int minimum, int maximum) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) {
            return fallback;
        }
        for (String part : query.split("&")) {
            int equals = part.indexOf('=');
            String key = equals >= 0 ? part.substring(0, equals) : part;
            if (!name.equals(URLDecoder.decode(key, StandardCharsets.UTF_8))) {
                continue;
            }
            String raw = equals >= 0 ? part.substring(equals + 1) : "";
            try {
                int value = Integer.parseInt(URLDecoder.decode(raw, StandardCharsets.UTF_8));
                if (value < minimum || value > maximum) {
                    throw new IllegalArgumentException(name + "超出允许范围");
                }
                return value;
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(name + "无效");
            }
        }
        return fallback;
    }

    private static int headerInteger(HttpExchange exchange, String name, int fallback, int minimum, int maximum) {
        String raw = exchange.getRequestHeaders().getFirst(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(name + "超出允许范围");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(name + "无效");
        }
    }

    private static String readSmallText(HttpExchange exchange, int maximumBytes) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(maximumBytes + 1);
        if (bytes.length > maximumBytes) {
            throw new IllegalArgumentException("请求内容过大");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String readResource(String path) throws IOException {
        try (InputStream input = RedactionServer.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("缺少内置界面资源");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void applySecurityHeaders(Headers headers) {
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        headers.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; " +
                "img-src 'self' data:; media-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; " +
                "frame-ancestors 'none'; form-action 'self'");
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        sendBytes(exchange, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(HttpExchange exchange, int status, String type, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String errorJson(String message) {
        return "{\"error\":" + JsonUtil.quote(message) + "}";
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return "请求处理失败";
        }
        message = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    @Override
    public void close() {
        server.stop(1);
        httpWorkers.shutdownNow();
    }
}

/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.server;

import io.github.caipeijia833.docredaction.processor.ProcessorRegistry;
import io.github.caipeijia833.docredaction.rules.RuleEngine;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactionServerIntegrationTest {
    private static final Pattern TOKEN = Pattern.compile("name=\"session-token\" content=\"([0-9a-f]{64})\"");
    private static final Pattern ID = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"");

    @TempDir
    Path temp;

    @Test
    void rejectsDnsRebindingHostBeforeExposingSessionToken() throws Exception {
        RuleEngine rules = RuleEngine.createDefault();
        JobService jobs = new JobService(new JobStore(temp.resolve("host-data")), new ProcessorRegistry(), rules);
        try (jobs; RedactionServer server = new RedactionServer(0, jobs, rules)) {
            server.start();
            String response;
            try (Socket socket = new Socket("127.0.0.1", server.port())) {
                socket.getOutputStream().write(("GET / HTTP/1.1\r\n"
                        + "Host: attacker.example\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }
            assertTrue(response.startsWith("HTTP/1.1 421"), response);
            assertFalse(TOKEN.matcher(response).find());
        }
    }

    @Test
    void protectsApiAndCompletesRawStreamingUpload() throws Exception {
        RuleEngine rules = RuleEngine.createDefault();
        JobService jobs = new JobService(new JobStore(temp.resolve("data")), new ProcessorRegistry(), rules);
        try (jobs; RedactionServer server = new RedactionServer(0, jobs, rules)) {
            server.start();
            URI base = URI.create("http://127.0.0.1:" + server.port());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

            HttpResponse<String> index = client.send(HttpRequest.newBuilder(base.resolve("/"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, index.statusCode());
            assertTrue(index.headers().firstValue("Content-Security-Policy").isPresent());
            Matcher tokenMatcher = TOKEN.matcher(index.body());
            assertTrue(tokenMatcher.find());
            String token = tokenMatcher.group(1);

            HttpResponse<String> config = client.send(HttpRequest.newBuilder(base.resolve("/api/config"))
                    .header("X-Session-Token", token).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, config.statusCode());
            assertTrue(config.body().contains("\"environmentProfile\":\"development\""), config.body());

            HttpResponse<String> capacity = client.send(HttpRequest.newBuilder(base.resolve("/api/capacity-estimate"))
                            .header("X-Session-Token", token)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"processingMode\":\"external_irreversible\","
                                    + "\"files\":[{\"name\":\"large.docx\",\"size\":536870912}]}"))
                            .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, capacity.statusCode(), capacity.body());
            assertTrue(capacity.body().contains("\"selectedBytes\":536870912"), capacity.body());
            assertTrue(capacity.body().contains("\"peakWorkspaceBytes\":1610612736"), capacity.body());
            assertTrue(capacity.body().contains("\"sufficient\":"), capacity.body());

            HttpResponse<String> filenamePreview = client.send(HttpRequest.newBuilder(base.resolve("/api/filename-preview"))
                            .header("X-Session-Token", token)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"projectId\":\"78787878-7878-7878-7878-787878787878\","
                                    + "\"names\":[\"手机号13800138000.pdf\"],\"categories\":[\"contact_location\"]}"))
                            .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, filenamePreview.statusCode(), filenamePreview.body());
            assertTrue(filenamePreview.body().contains("\"redactedName\":\"手机号REDACTED_脱敏.pdf\""),
                    filenamePreview.body());

            HttpResponse<String> forbidden = client.send(HttpRequest.newBuilder(base.resolve("/api/jobs"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(403, forbidden.statusCode());

            byte[] docx = syntheticDocx();
            String longChineseProject = "项目".repeat(60);
            HttpRequest upload = HttpRequest.newBuilder(base.resolve("/api/jobs"))
                    .header("X-Session-Token", token)
                    .header("X-Filename", URLEncoder.encode("中文合成材料.docx", StandardCharsets.UTF_8))
                    .header("X-Project-Name", URLEncoder.encode(longChineseProject, StandardCharsets.UTF_8))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(docx)).build();
            HttpResponse<String> accepted = client.send(upload,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(202, accepted.statusCode(), accepted.body());
            Matcher idMatcher = ID.matcher(accepted.body());
            assertTrue(idMatcher.find());
            String id = idMatcher.group(1);

            String status = "";
            for (int attempt = 0; attempt < 50; attempt++) {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(base.resolve("/api/jobs/" + id))
                        .header("X-Session-Token", token).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                status = response.body();
                if (status.contains("\"status\":\"COMPLETED\"") || status.contains("\"status\":\"FAILED\"")) break;
                Thread.sleep(50);
            }
            assertTrue(status.contains("\"status\":\"COMPLETED\""), status);
            assertTrue(status.contains("\"totalMatches\":2"), status);
            assertTrue(status.contains("\"projectName\":\"" + longChineseProject.substring(0, 100) + "\""), status);

            Path downloaded = temp.resolve("downloaded.docx");
            HttpResponse<Path> result = client.send(HttpRequest.newBuilder(base.resolve("/api/jobs/" + id + "/download"))
                    .header("X-Session-Token", token).GET().build(), HttpResponse.BodyHandlers.ofFile(downloaded));
            assertEquals(200, result.statusCode());
            try (XWPFDocument document = new XWPFDocument(Files.newInputStream(downloaded))) {
                String text = document.getParagraphs().getFirst().getText();
                assertFalse(text.contains("13800138000"));
                assertFalse(text.contains("synthetic@example.com"));
            }
        }
    }

    @Test
    void restoresEncryptedOriginalAndExposesCustomRuleWorkbenchApis() throws Exception {
        RuleEngine rules = RuleEngine.createDefault(temp.resolve("config/rules.properties"));
        JobService jobs = new JobService(new JobStore(temp.resolve("data-restore")), new ProcessorRegistry(), rules);
        try (jobs; RedactionServer server = new RedactionServer(0, jobs, rules)) {
            server.start();
            URI base = URI.create("http://127.0.0.1:" + server.port());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            HttpResponse<String> index = client.send(HttpRequest.newBuilder(base.resolve("/"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Matcher tokenMatcher = TOKEN.matcher(index.body());
            assertTrue(tokenMatcher.find());
            String token = tokenMatcher.group(1);

            HttpResponse<String> added = client.send(HttpRequest.newBuilder(base.resolve("/api/rules/custom"))
                            .header("X-Session-Token", token)
                            .header("X-Rule-Id", "CASE_CODE")
                            .header("X-Rule-Label", URLEncoder.encode("内部编号", StandardCharsets.UTF_8))
                            .header("X-Rule-Category", "legal_case")
                            .header("X-Rule-Priority", "80")
                            .POST(HttpRequest.BodyPublishers.ofString("CASE-[0-9]{6}"))
                            .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(201, added.statusCode(), added.body());
            assertTrue(added.body().contains("CUSTOM_CASE_CODE"));

            HttpResponse<String> tested = client.send(HttpRequest.newBuilder(base.resolve("/api/rules/test"))
                            .header("X-Session-Token", token)
                            .header("X-Rule-Regex", URLEncoder.encode("CASE-[0-9]{6}", StandardCharsets.UTF_8))
                            .POST(HttpRequest.BodyPublishers.ofString("编号 CASE-123456"))
                            .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, tested.statusCode(), tested.body());
            assertTrue(tested.body().contains("\"count\":1"));

            HttpResponse<String> updated = client.send(HttpRequest.newBuilder(
                                    base.resolve("/api/rules/CUSTOM_CASE_CODE/definition"))
                            .header("X-Session-Token", token)
                            .header("X-Rule-Label", URLEncoder.encode("文件编号", StandardCharsets.UTF_8))
                            .header("X-Rule-Category", "custom")
                            .header("X-Rule-Priority", "70")
                            .PUT(HttpRequest.BodyPublishers.ofString("DOC-[0-9]{6}"))
                            .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, updated.statusCode(), updated.body());
            HttpResponse<String> exported = client.send(HttpRequest.newBuilder(base.resolve("/api/rules/export"))
                            .header("X-Session-Token", token).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, exported.statusCode());
            assertTrue(exported.body().contains("doc-redaction-custom-rules/v1"));
            assertTrue(exported.body().contains("DOC-[0-9]{6}"));

            byte[] original = syntheticDocx();
            String passphrase = "local-restore-passphrase";
            String projectId = "89898989-8989-8989-8989-898989898989";
            HttpResponse<String> upload = client.send(HttpRequest.newBuilder(base.resolve("/api/jobs"))
                            .header("X-Session-Token", token)
                            .header("X-Filename", "restorable.docx")
                            .header("X-Processing-Mode", "reversible_vault")
                            .header("X-Project-Id", projectId)
                            .header("X-Vault-Passphrase", URLEncoder.encode(passphrase, StandardCharsets.UTF_8))
                            .POST(HttpRequest.BodyPublishers.ofByteArray(original)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(202, upload.statusCode(), upload.body());
            Matcher idMatcher = ID.matcher(upload.body());
            assertTrue(idMatcher.find());
            String id = idMatcher.group(1);
            for (int attempt = 0; attempt < 80; attempt++) {
                HttpResponse<String> status = client.send(HttpRequest.newBuilder(base.resolve("/api/jobs/" + id))
                                .header("X-Session-Token", token).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (status.body().contains("\"status\":\"COMPLETED\"")) break;
                Thread.sleep(50);
            }
            HttpResponse<byte[]> restored = client.send(HttpRequest.newBuilder(base.resolve("/api/jobs/" + id + "/restore"))
                            .header("X-Session-Token", token)
                            .header("X-Vault-Passphrase", URLEncoder.encode(passphrase, StandardCharsets.UTF_8))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, restored.statusCode());
            assertTrue(java.util.Arrays.equals(original, restored.body()));

            HttpResponse<String> report = client.send(HttpRequest.newBuilder(
                            base.resolve("/api/projects/" + projectId + "/report"))
                            .header("X-Session-Token", token).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, report.statusCode(), report.body());
            assertTrue(report.body().contains("doc-redaction-project-report/v1"), report.body());
            assertTrue(report.body().contains("\"completedCount\":1"), report.body());

            Path projectResults = temp.resolve("project-results.zip");
            HttpResponse<Path> results = client.send(HttpRequest.newBuilder(
                            base.resolve("/api/projects/" + projectId + "/download"))
                            .header("X-Session-Token", token).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(projectResults));
            assertEquals(200, results.statusCode());
            try (ZipFile zip = ZipFile.builder().setPath(projectResults).get()) {
                assertTrue(zip.getEntry("_redaction_report.json") != null);
                assertTrue(zip.getEntries().asIterator().hasNext());
            }

            Path batchRestore = temp.resolve("batch-restore.zip");
            HttpResponse<Path> batch = client.send(HttpRequest.newBuilder(
                            base.resolve("/api/projects/" + projectId + "/restore"))
                            .header("X-Session-Token", token)
                            .header("X-Vault-Passphrase", URLEncoder.encode(passphrase, StandardCharsets.UTF_8))
                            .header("X-Restore-Confirmation", "RESTORE_SELECTED_ORIGINALS")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"jobIds\":[\"" + id + "\"]}"))
                            .build(), HttpResponse.BodyHandlers.ofFile(batchRestore));
            assertEquals(200, batch.statusCode());
            try (ZipFile zip = ZipFile.builder().setPath(batchRestore).get()) {
                assertTrue(zip.getEntry("restorable.docx") != null);
                assertTrue(zip.getEntry("_restore_manifest.json") != null);
            }
        }
    }

    private static byte[] syntheticDocx() throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("手机13800138000，邮箱synthetic@example.com");
            document.write(output);
            return output.toByteArray();
        }
    }
}

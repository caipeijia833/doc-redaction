/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Offline Qwen3-VL adapter. The model is an additive detector: callers may add
 * masks proposed here, but this class never removes deterministic OCR/rule masks.
 */
public final class LocalVlmEngine {
    public static final String MODEL_ID = "Qwen/Qwen3-VL-2B-Instruct-GGUF:Q4_K_M";
    private static final String MODEL_FILE = "Qwen3VL-2B-Instruct-Q4_K_M.gguf";
    private static final String PROJECTOR_FILE = "mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf";
    private static final int MAX_DETECTIONS = 200;
    private static final int MAX_EDGE = 1600;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TYPES = Set.of("sensitive_text", "identity_document", "bank_card",
            "vehicle_plate", "property_document", "face", "signature", "fingerprint", "qr_code");
    private static final Object SESSION_LOCK = new Object();
    private static volatile ServerSession session;

    private final Capability capability;
    private final long inferenceTimeoutSeconds;

    public LocalVlmEngine() {
        this.capability = inspect();
        this.inferenceTimeoutSeconds = boundedLongProperty("docredaction.vlm.timeoutSeconds", 180L, 15L, 900L);
    }

    public Capability capability() {
        return capability;
    }

    public boolean required() {
        return "required".equals(capability.mode());
    }

    public List<Detection> detect(BufferedImage image) throws IOException {
        if (image == null) {
            throw new IllegalArgumentException("图像不能为空");
        }
        if (!capability.available()) {
            if (required()) {
                throw new IOException(capability.message());
            }
            return List.of();
        }
        ScaledImage scaled = scale(image);
        byte[] jpeg;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(scaled.image(), "jpg", output)) {
                throw new IOException("无法为本地多模态模型编码图像");
            }
            jpeg = output.toByteArray();
        }
        ServerSession current = server();
        JsonNode response = current.analyze(jpeg, inferenceTimeoutSeconds);
        List<Detection> parsed = parseDetections(response, scaled.image().getWidth(), scaled.image().getHeight());
        if (scaled.scaleX() == 1.0d && scaled.scaleY() == 1.0d) {
            return parsed;
        }
        return parsed.stream().map(item -> item.scale(scaled.scaleX(), scaled.scaleY(),
                image.getWidth(), image.getHeight())).toList();
    }

    static List<Detection> parseDetections(JsonNode root, int width, int height) throws IOException {
        JsonNode findings = root.path("findings");
        if (!findings.isArray()) {
            throw new IOException("本地多模态模型未返回findings数组");
        }
        List<Detection> result = new ArrayList<>();
        for (JsonNode item : findings) {
            if (result.size() >= MAX_DETECTIONS) {
                throw new IOException("本地多模态模型返回的区域超过安全上限");
            }
            String type = item.path("type").asText("").toLowerCase(Locale.ROOT);
            JsonNode box = item.path("bbox");
            if (!TYPES.contains(type) || !box.isArray() || box.size() != 4) {
                continue;
            }
            double x1 = box.get(0).asDouble(-1.0d);
            double y1 = box.get(1).asDouble(-1.0d);
            double x2 = box.get(2).asDouble(-1.0d);
            double y2 = box.get(3).asDouble(-1.0d);
            if (x1 < 0.0d || y1 < 0.0d || x2 <= x1 || y2 <= y1
                    || x2 > 1000.0d || y2 > 1000.0d) {
                continue;
            }
            int left = clamp((int) Math.floor(x1 / 1000.0d * width), 0, width - 1);
            int top = clamp((int) Math.floor(y1 / 1000.0d * height), 0, height - 1);
            int right = clamp((int) Math.ceil(x2 / 1000.0d * width), left + 1, width);
            int bottom = clamp((int) Math.ceil(y2 / 1000.0d * height), top + 1, height);
            long area = (right - left) * (long) (bottom - top);
            long imageArea = width * (long) height;
            if (area < 16L || area * 100L > imageArea * 85L) {
                continue;
            }
            double confidence = Math.max(0.0d, Math.min(1.0d, item.path("confidence").asDouble(0.75d)));
            String label = item.path("label").asText(type).replaceAll("[\\r\\n\\t]+", " ").trim();
            if (label.length() > 120) label = label.substring(0, 120);
            result.add(new Detection(type, label, left, top, right - left, bottom - top, confidence));
        }
        return List.copyOf(result);
    }

    private ServerSession server() throws IOException {
        ServerSession current = session;
        if (current != null && current.matches(capability) && current.alive()) {
            return current;
        }
        synchronized (SESSION_LOCK) {
            current = session;
            if (current != null && current.matches(capability) && current.alive()) return current;
            if (current != null) current.close();
            session = new ServerSession(capability);
            return session;
        }
    }

    public static Capability inspect() {
        String mode = System.getProperty("docredaction.vlm.mode", "auto").trim().toLowerCase(Locale.ROOT);
        if (!Set.of("auto", "disabled", "required").contains(mode)) mode = "auto";
        if ("disabled".equals(mode)) {
            return new Capability(false, mode, null, null, null, MODEL_ID, "", "本地多模态模型已停用");
        }
        Path executable = configuredPath("docredaction.vlm.executable", defaultExecutable());
        Path model = configuredPath("docredaction.vlm.model", Path.of("vlm", "models", MODEL_FILE));
        Path projector = configuredPath("docredaction.vlm.mmproj", Path.of("vlm", "models", PROJECTOR_FILE));
        List<String> missing = new ArrayList<>();
        if (executable == null || !Files.isRegularFile(executable)) missing.add("llama-server");
        if (model == null || !Files.isRegularFile(model) || fileSize(model) < 900L * 1024L * 1024L) missing.add(MODEL_FILE);
        if (projector == null || !Files.isRegularFile(projector) || fileSize(projector) < 300L * 1024L * 1024L) missing.add(PROJECTOR_FILE);
        if (!missing.isEmpty()) {
            return new Capability(false, mode, executable, model, projector, MODEL_ID, "",
                    "缺少本地Qwen3-VL组件：" + String.join("、", missing));
        }
        String version = version(executable);
        return new Capability(true, mode, executable, model, projector, MODEL_ID, version,
                "Qwen3-VL-2B Q4_K_M本地增强检测可用");
    }

    private static Path configuredPath(String property, Path fallback) {
        String value = System.getProperty(property, "").trim();
        Path path = value.isBlank() ? fallback : Path.of(value);
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    private static Path defaultExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return Path.of("vlm", "bin", windows ? "llama-server.exe" : "llama-server");
    }

    private static long fileSize(Path path) {
        try { return Files.size(path); } catch (IOException ex) { return -1L; }
    }

    private static String version(Path executable) {
        Process process = null;
        try {
            process = new ProcessBuilder(executable.toString(), "--version").redirectErrorStream(true).start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly(); return "";
            }
            return new String(process.getInputStream().readNBytes(16 * 1024), StandardCharsets.UTF_8)
                    .lines().findFirst().orElse("").trim();
        } catch (Exception ex) {
            if (process != null) process.destroyForcibly();
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            return "";
        }
    }

    private static ScaledImage scale(BufferedImage source) {
        double ratio = Math.min(1.0d, MAX_EDGE / (double) Math.max(source.getWidth(), source.getHeight()));
        if (ratio >= 1.0d) return new ScaledImage(source, 1.0d, 1.0d);
        int width = Math.max(1, (int) Math.round(source.getWidth() * ratio));
        int height = Math.max(1, (int) Math.round(source.getHeight() * ratio));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally { graphics.dispose(); }
        return new ScaledImage(target, source.getWidth() / (double) width, source.getHeight() / (double) height);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long boundedLongProperty(String name, long fallback, long minimum, long maximum) {
        try { return Math.max(minimum, Math.min(maximum, Long.parseLong(System.getProperty(name, Long.toString(fallback))))); }
        catch (NumberFormatException ex) { return fallback; }
    }

    public record Capability(boolean available, String mode, Path executable, Path model, Path projector,
            String modelId, String runtimeVersion, String message) { }

    public record Detection(String type, String label, int x, int y, int width, int height, double confidence) {
        Detection scale(double scaleX, double scaleY, int maximumWidth, int maximumHeight) {
            int left = clamp((int) Math.round(x * scaleX), 0, maximumWidth - 1);
            int top = clamp((int) Math.round(y * scaleY), 0, maximumHeight - 1);
            int right = clamp((int) Math.round((x + width) * scaleX), left + 1, maximumWidth);
            int bottom = clamp((int) Math.round((y + height) * scaleY), top + 1, maximumHeight);
            return new Detection(type, label, left, top, right - left, bottom - top, confidence);
        }
    }

    private record ScaledImage(BufferedImage image, double scaleX, double scaleY) { }

    private static final class ServerSession implements AutoCloseable {
        private static final String PROMPT = """
                You are an offline document privacy detector. Inspect the image and locate only high-confidence sensitive regions.
                Include personal identifiers, names tied to records, addresses, phone/email, account or card numbers, identity/property/vehicle documents, license plates, faces, signatures, fingerprints and QR codes likely to encode private data.
                Do not transcribe secrets. Do not return prose. Return JSON only using coordinates normalized to 0..1000:
                {"findings":[{"type":"sensitive_text","label":"brief category","bbox":[x1,y1,x2,y2],"confidence":0.0}]}
                Allowed type values: sensitive_text, identity_document, bank_card, vehicle_plate, property_document, face, signature, fingerprint, qr_code.
                If uncertain or no sensitive region exists, return {"findings":[]}.
                """;

        private final Capability capability;
        private final Process process;
        private final URI endpoint;
        private final HttpClient client;
        private final String apiKey;

        ServerSession(Capability capability) throws IOException {
            this.capability = capability;
            int port = freePort();
            this.apiKey = randomApiKey();
            this.endpoint = URI.create("http://127.0.0.1:" + port + "/v1/chat/completions");
            this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            List<String> command = new ArrayList<>(List.of(capability.executable().toString(),
                    "-m", capability.model().toString(), "--mmproj", capability.projector().toString(),
                    "--alias", MODEL_ID,
                    "--api-key", apiKey,
                    "--host", "127.0.0.1", "--port", Integer.toString(port), "-c", "4096",
                    "-ngl", "99", "-np", "1"));
            this.process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(this::close));
            waitUntilReady();
        }

        synchronized JsonNode analyze(byte[] jpeg, long timeoutSeconds) throws IOException {
            ObjectNode root = JSON.createObjectNode();
            root.put("model", MODEL_ID);
            root.put("temperature", 0.0d);
            root.put("max_tokens", 1200);
            ObjectNode template = root.putObject("chat_template_kwargs"); template.put("enable_thinking", false);
            ArrayNode messages = root.putArray("messages");
            ObjectNode user = messages.addObject(); user.put("role", "user");
            ArrayNode content = user.putArray("content");
            content.addObject().put("type", "text").put("text", PROMPT);
            content.addObject().put("type", "image_url").putObject("image_url")
                    .put("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg));
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(root.toString())).build();
            try {
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200 || response.body().length > MAX_RESPONSE_BYTES) {
                    throw new IOException("本地多模态模型请求失败（HTTP " + response.statusCode() + "）");
                }
                JsonNode envelope = JSON.readTree(response.body());
                String contentValue = envelope.path("choices").path(0).path("message").path("content").asText("").trim();
                int start = contentValue.indexOf('{');
                int end = contentValue.lastIndexOf('}');
                if (start < 0 || end < start) throw new IOException("本地多模态模型响应不是有效JSON");
                return JSON.readTree(contentValue.substring(start, end + 1));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt(); throw new IOException("本地多模态模型处理已取消", ex);
            }
        }

        boolean matches(Capability value) {
            return capability.executable().equals(value.executable()) && capability.model().equals(value.model())
                    && capability.projector().equals(value.projector());
        }

        boolean alive() { return process.isAlive(); }

        private void waitUntilReady() throws IOException {
            URI health = URI.create(endpoint.getScheme() + "://" + endpoint.getAuthority() + "/health");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120L);
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) throw new IOException("llama-server在模型加载期间退出");
                try {
                    HttpResponse<Void> response = client.send(HttpRequest.newBuilder(health)
                            .timeout(Duration.ofSeconds(2)).header("Authorization", "Bearer " + apiKey)
                            .GET().build(), HttpResponse.BodyHandlers.discarding());
                    if (response.statusCode() == 200) return;
                } catch (IOException ignored) {
                    // The loopback server is still loading the model.
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt(); throw new IOException("等待本地多模态模型时被取消", ex);
                }
                try { Thread.sleep(250L); } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt(); throw new IOException("等待本地多模态模型时被取消", ex);
                }
            }
            close(); throw new IOException("本地多模态模型在120秒内未能启动");
        }

        private static int freePort() throws IOException {
            try (ServerSocket socket = new ServerSocket()) {
                socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                return socket.getLocalPort();
            }
        }

        private static String randomApiKey() {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            return HexFormat.of().formatHex(bytes);
        }

        @Override public void close() {
            process.destroy();
            try { if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly(); }
            catch (InterruptedException ex) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
        }
    }
}

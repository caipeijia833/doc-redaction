/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.update;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.Comparator;

/** Verifies complete, Ed25519-signed offline update payloads before mutation. */
public final class OfflineUpdateSecurity {
    private static final int MAX_MANIFEST_BYTES = 4 * 1024 * 1024;
    private static final int MAX_SIGNATURE_BYTES = 1024;
    private static final int MAX_KEYRING_BYTES = 64 * 1024;
    private static final int MAX_FILES = 50_000;
    private static final long MAX_PAYLOAD_BYTES = 24L * 1024 * 1024 * 1024;
    private static final Set<String> ALLOWED_DIRECTORIES = Set.of(
            "app", "runtime", "ocr", "media", "vlm", "repair", "samples", "update");
    private static final Set<String> ALLOWED_ROOT_FILES = Set.of(
            "start-windows.bat", "preflight-windows.ps1", "start-macos.command", "preflight-macos.sh",
            "readme.md", "sbom.cdx.json", "third_party_notices.md", "sha256sums.txt", "version",
            "data_schema_version");
    private static final ObjectMapper JSON = new ObjectMapper(
            com.fasterxml.jackson.core.JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    private OfflineUpdateSecurity() {
    }

    public static Verification verify(Path manifestFile, Path signatureFile, Path trustedKeysFile,
            Path payloadRoot) throws Exception {
        byte[] manifest = boundedRead(manifestFile, MAX_MANIFEST_BYTES, "update manifest");
        byte[] encodedSignature = boundedRead(signatureFile, MAX_SIGNATURE_BYTES, "update signature");
        byte[] keyringBytes = boundedRead(trustedKeysFile, MAX_KEYRING_BYTES, "trusted update keyring");
        JsonNode root = JSON.readTree(manifest);
        if (root == null || !root.isObject() || root.path("formatVersion").asInt(-1) != 1
                || !"doc-redaction".equals(root.path("product").asText())) {
            throw new IOException("Unsupported offline update manifest");
        }
        String version = requiredText(root, "version", "[0-9A-Za-z][0-9A-Za-z._+-]{0,63}");
        if (root.path("dataSchemaVersion").asInt(-1) != 1) {
            throw new IOException("This updater only supports data schema version 1");
        }
        String keyId = requiredText(root, "keyId", "[A-Za-z0-9._-]{1,80}");
        PublicKey publicKey = trustedKey(keyringBytes, keyId);
        byte[] signatureBytes;
        try {
            signatureBytes = Base64.getMimeDecoder().decode(encodedSignature);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Update signature is not valid Base64", ex);
        }
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(manifest);
        if (!verifier.verify(signatureBytes)) {
            throw new IOException("Offline update signature verification failed");
        }

        Path payload = payloadRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(payload, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(payload)) {
            throw new IOException("Offline update payload is not a real directory");
        }
        JsonNode files = root.path("files");
        if (!files.isArray() || files.isEmpty() || files.size() > MAX_FILES) {
            throw new IOException("Offline update file list is empty or exceeds the safety limit");
        }
        Set<String> expectedPaths = new HashSet<>();
        long totalBytes = 0L;
        for (JsonNode file : files) {
            String relative = requiredText(file, "path", "[^\\r\\n]{1,500}").replace('\\', '/');
            validateRelativePath(relative);
            if (!expectedPaths.add(relative)) {
                throw new IOException("Duplicate update payload path: " + relative);
            }
            long declaredSize = file.path("size").asLong(-1L);
            String declaredHash = requiredText(file, "sha256", "[0-9a-fA-F]{64}")
                    .toLowerCase(Locale.ROOT);
            Path target = payload.resolve(relative).normalize();
            if (!target.startsWith(payload) || containsSymbolicLink(payload, target)
                    || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Unsafe or missing update payload file: " + relative);
            }
            long actualSize = Files.size(target);
            if (declaredSize < 0L || actualSize != declaredSize) {
                throw new IOException("Update payload size mismatch: " + relative);
            }
            totalBytes = Math.addExact(totalBytes, actualSize);
            if (totalBytes > MAX_PAYLOAD_BYTES) {
                throw new IOException("Offline update payload exceeds 24 GiB");
            }
            byte[] expectedHash = java.util.HexFormat.of().parseHex(declaredHash);
            byte[] actualHash = sha256(target);
            if (!MessageDigest.isEqual(expectedHash, actualHash)) {
                throw new IOException("Update payload SHA-256 mismatch: " + relative);
            }
        }

        Set<String> actualPaths = new HashSet<>();
        try (var paths = Files.walk(payload)) {
            for (Path path : paths.toList()) {
                if (path.equals(payload)) {
                    continue;
                }
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("Symbolic links are forbidden in offline updates");
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    actualPaths.add(payload.relativize(path).toString().replace('\\', '/'));
                }
            }
        }
        if (!actualPaths.equals(expectedPaths)) {
            throw new IOException("Offline update contains unlisted or missing files");
        }
        if (!version.equals(Files.readString(payload.resolve("VERSION"), StandardCharsets.UTF_8).trim())) {
            throw new IOException("Update VERSION file does not match the signed manifest");
        }
        if (!"1".equals(Files.readString(payload.resolve("DATA_SCHEMA_VERSION"),
                StandardCharsets.UTF_8).trim())) {
            throw new IOException("Update data schema marker does not match the signed manifest");
        }
        return new Verification(version, keyId, expectedPaths.size(), totalBytes);
    }

    public static void sign(Path manifestFile, Path privateKeyFile, Path signatureFile) throws Exception {
        byte[] manifest = boundedRead(manifestFile, MAX_MANIFEST_BYTES, "update manifest");
        byte[] privateBytes = boundedRead(privateKeyFile, 4096, "Ed25519 private key");
        PrivateKey key = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(manifest);
        Files.writeString(signatureFile, Base64.getEncoder().encodeToString(signer.sign()) + "\n",
                StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    public static void generateKeyPair(Path privateKeyFile, Path publicKeyringFile, String keyId) throws Exception {
        if (keyId == null || !keyId.matches("[A-Za-z0-9._-]{1,80}")) {
            throw new IllegalArgumentException("Invalid update signing key ID");
        }
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Files.write(privateKeyFile, pair.getPrivate().getEncoded(), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        String line = "key." + keyId + '=' + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) + "\n";
        Files.writeString(publicKeyringFile, line, StandardCharsets.US_ASCII,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    public static Verification createManifest(Path payloadRoot, String version, String keyId,
            Path manifestFile) throws Exception {
        if (version == null || !version.matches("[0-9A-Za-z][0-9A-Za-z._+-]{0,63}")) {
            throw new IllegalArgumentException("Invalid update version");
        }
        if (keyId == null || !keyId.matches("[A-Za-z0-9._-]{1,80}")) {
            throw new IllegalArgumentException("Invalid update signing key ID");
        }
        Path payload = payloadRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(payload, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(payload)) {
            throw new IOException("Offline update payload is not a real directory");
        }
        if (!version.equals(Files.readString(payload.resolve("VERSION"), StandardCharsets.UTF_8).trim())
                || !"1".equals(Files.readString(payload.resolve("DATA_SCHEMA_VERSION"),
                        StandardCharsets.UTF_8).trim())) {
            throw new IOException("Update version or data schema marker does not match");
        }
        ObjectNode root = JSON.createObjectNode();
        root.put("formatVersion", 1);
        root.put("product", "doc-redaction");
        root.put("version", version);
        root.put("dataSchemaVersion", 1);
        root.put("keyId", keyId);
        root.put("generatedAt", java.time.Instant.now().toString());
        ArrayNode files = root.putArray("files");
        int count = 0;
        long totalBytes = 0L;
        try (var paths = Files.walk(payload)) {
            for (Path path : paths.sorted(Comparator.comparing(Path::toString)).toList()) {
                if (path.equals(payload)) {
                    continue;
                }
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("Symbolic links are forbidden in offline updates");
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String relative = payload.relativize(path).toString().replace('\\', '/');
                validateRelativePath(relative);
                long size = Files.size(path);
                totalBytes = Math.addExact(totalBytes, size);
                if (++count > MAX_FILES || totalBytes > MAX_PAYLOAD_BYTES) {
                    throw new IOException("Offline update exceeds its file or byte limit");
                }
                ObjectNode file = files.addObject();
                file.put("path", relative);
                file.put("size", size);
                file.put("sha256", java.util.HexFormat.of().formatHex(sha256(path)));
            }
        }
        if (count == 0) {
            throw new IOException("Offline update payload is empty");
        }
        byte[] manifestBytes = JSON.writeValueAsBytes(root);
        Files.write(manifestFile, manifestBytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return new Verification(version, keyId, count, totalBytes);
    }

    private static PublicKey trustedKey(byte[] keyringBytes, String keyId) throws Exception {
        Properties properties = new Properties();
        try (Reader reader = new java.io.StringReader(new String(keyringBytes, StandardCharsets.UTF_8))) {
            properties.load(reader);
        }
        String encoded = properties.getProperty("key." + keyId, "").trim();
        if (encoded.isBlank()) {
            throw new IOException("Update signing key is not trusted: " + keyId);
        }
        byte[] publicBytes;
        try {
            publicBytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Trusted update public key is invalid", ex);
        }
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(publicBytes));
    }

    private static byte[] boundedRead(Path file, int maximum, String label) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            throw new IOException("Missing or unsafe " + label);
        }
        long size = Files.size(normalized);
        if (size <= 0L || size > maximum) {
            throw new IOException(label + " exceeds its safety limit");
        }
        return Files.readAllBytes(normalized);
    }

    private static String requiredText(JsonNode node, String field, String pattern) throws IOException {
        JsonNode value = node.path(field);
        String text = value.isTextual() ? value.textValue() : "";
        if (!text.matches(pattern)) {
            throw new IOException("Invalid update manifest field: " + field);
        }
        return text;
    }

    private static void validateRelativePath(String relative) throws IOException {
        if (relative.startsWith("/") || relative.endsWith("/") || relative.contains(":")
                || relative.contains("//") || relative.contains("\u0000")) {
            throw new IOException("Unsafe update payload path: " + relative);
        }
        String[] segments = relative.split("/");
        for (String segment : segments) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IOException("Unsafe update payload path: " + relative);
            }
        }
        String first = segments[0].toLowerCase(Locale.ROOT);
        if (segments.length == 1 ? !ALLOWED_ROOT_FILES.contains(first) : !ALLOWED_DIRECTORIES.contains(first)) {
            throw new IOException("Update path is outside the product allowlist: " + relative);
        }
        if ("data".equals(first)) {
            throw new IOException("Offline updates may never contain user data");
        }
    }

    private static boolean containsSymbolicLink(Path root, Path target) {
        Path current = target;
        while (current != null && !current.equals(root)) {
            if (Files.isSymbolicLink(current)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private static byte[] sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    public record Verification(String version, String keyId, int fileCount, long totalBytes) {
    }
}

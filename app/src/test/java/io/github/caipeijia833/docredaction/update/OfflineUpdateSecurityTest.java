/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfflineUpdateSecurityTest {
    @TempDir
    Path temp;

    @Test
    void createsManifestThatRoundTripsThroughSignatureVerification() throws Exception {
        Path payload = Files.createDirectory(temp.resolve("created-payload"));
        Files.writeString(payload.resolve("VERSION"), "1.2.3", StandardCharsets.UTF_8);
        Files.writeString(payload.resolve("DATA_SCHEMA_VERSION"), "1", StandardCharsets.UTF_8);
        Files.writeString(payload.resolve("README.md"), "release", StandardCharsets.UTF_8);
        Path manifest = temp.resolve("created-manifest.json");
        var created = OfflineUpdateSecurity.createManifest(payload, "1.2.3", "release-key", manifest);
        Path privateKey = temp.resolve("created-private.pk8");
        Path keyring = temp.resolve("created-trusted.properties");
        Path signature = temp.resolve("created.sig");
        OfflineUpdateSecurity.generateKeyPair(privateKey, keyring, "release-key");
        OfflineUpdateSecurity.sign(manifest, privateKey, signature);

        var verified = OfflineUpdateSecurity.verify(manifest, signature, keyring, payload);
        assertEquals(created.fileCount(), verified.fileCount());
        assertEquals(created.totalBytes(), verified.totalBytes());
    }

    @Test
    void verifiesSignedCompletePayloadAndRejectsTampering() throws Exception {
        Path payload = Files.createDirectories(temp.resolve("payload/app"));
        Path application = Files.writeString(payload.resolve("doc-redaction-poc.jar"), "safe-update",
                StandardCharsets.UTF_8);
        Path payloadRoot = payload.getParent();
        Path versionFile = Files.writeString(payloadRoot.resolve("VERSION"), "0.2.0", StandardCharsets.UTF_8);
        Path schemaFile = Files.writeString(payloadRoot.resolve("DATA_SCHEMA_VERSION"), "1", StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(application)));
        String versionHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(versionFile)));
        String schemaHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(schemaFile)));
        Path manifest = Files.writeString(temp.resolve("manifest.json"), """
                {"formatVersion":1,"product":"doc-redaction","version":"0.2.0","dataSchemaVersion":1,"keyId":"test-key","files":[
                {"path":"DATA_SCHEMA_VERSION","size":1,"sha256":"%s"},
                {"path":"VERSION","size":5,"sha256":"%s"},
                {"path":"app/doc-redaction-poc.jar","size":11,"sha256":"%s"}]}
                """.formatted(schemaHash, versionHash, hash), StandardCharsets.UTF_8);
        Path privateKey = temp.resolve("private.pk8");
        Path keyring = temp.resolve("trusted.properties");
        Path signature = temp.resolve("manifest.sig");
        OfflineUpdateSecurity.generateKeyPair(privateKey, keyring, "test-key");
        OfflineUpdateSecurity.sign(manifest, privateKey, signature);

        var verified = OfflineUpdateSecurity.verify(manifest, signature, keyring, payloadRoot);
        assertEquals("0.2.0", verified.version());
        assertEquals(3, verified.fileCount());
        assertEquals(17L, verified.totalBytes());

        Files.writeString(application, "tampered", StandardCharsets.UTF_8);
        IOException error = assertThrows(IOException.class,
                () -> OfflineUpdateSecurity.verify(manifest, signature, keyring, payloadRoot));
        assertTrue(error.getMessage().contains("size mismatch") || error.getMessage().contains("SHA-256"));
    }

    @Test
    void rejectsUserDataAndUnlistedFiles() throws Exception {
        Path payload = Files.createDirectories(temp.resolve("bad-payload/data"));
        Path userData = Files.writeString(payload.resolve("jobs.db"), "must-not-update", StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(userData)));
        Path manifest = Files.writeString(temp.resolve("bad-manifest.json"), """
                {"formatVersion":1,"product":"doc-redaction","version":"0.2.0","dataSchemaVersion":1,"keyId":"test-key","files":[
                {"path":"data/jobs.db","size":15,"sha256":"%s"}]}
                """.formatted(hash), StandardCharsets.UTF_8);
        Path privateKey = temp.resolve("bad-private.pk8");
        Path keyring = temp.resolve("bad-trusted.properties");
        Path signature = temp.resolve("bad-manifest.sig");
        OfflineUpdateSecurity.generateKeyPair(privateKey, keyring, "test-key");
        OfflineUpdateSecurity.sign(manifest, privateKey, signature);

        IOException error = assertThrows(IOException.class,
                () -> OfflineUpdateSecurity.verify(manifest, signature, keyring, payload.getParent()));
        assertTrue(error.getMessage().contains("allowlist") || error.getMessage().contains("user data"));
    }
}

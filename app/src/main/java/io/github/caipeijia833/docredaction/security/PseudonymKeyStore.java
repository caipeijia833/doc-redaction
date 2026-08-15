/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Set;

public final class PseudonymKeyStore {
    private static final int KEY_LENGTH = 32;
    private final byte[] masterKey;

    public PseudonymKeyStore(Path configDirectory) throws IOException {
        Files.createDirectories(configDirectory);
        restrictPermissions(configDirectory, true);
        Path keyFile = configDirectory.resolve("pseudonym-master.key");
        if (Files.isRegularFile(keyFile)) {
            restrictPermissions(keyFile, false);
            masterKey = Files.readAllBytes(keyFile);
            if (masterKey.length != KEY_LENGTH) {
                throw new IOException("本地一致替换密钥格式无效");
            }
        } else {
            byte[] generated = new byte[KEY_LENGTH];
            new SecureRandom().nextBytes(generated);
            try {
                Files.write(keyFile, generated, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                restrictPermissions(keyFile, false);
                masterKey = generated.clone();
            } finally {
                Arrays.fill(generated, (byte) 0);
            }
        }
    }

    private static void restrictPermissions(Path path, boolean directory) throws IOException {
        if (Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView.class) == null) {
            return; // Windows uses the ACL inherited from the user-owned data directory.
        }
        Set<PosixFilePermission> permissions = directory
                ? Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE)
                : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        Files.setPosixFilePermissions(path, permissions);
    }

    public byte[] projectKey(String projectName) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(masterKey, "HmacSHA256"));
            mac.update("doc-redaction-project/v1\0".getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(projectName.strip().getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("无法生成项目一致替换密钥", ex);
        }
    }
}

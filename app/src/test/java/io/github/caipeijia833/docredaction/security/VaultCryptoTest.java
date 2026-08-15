/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VaultCryptoTest {
    @TempDir
    Path temp;

    @Test
    void encryptsRestoresAndRejectsWrongPassphrase() throws Exception {
        Path original = temp.resolve("原件.bin");
        Path vault = temp.resolve("vault/original.drenc");
        byte[] content = "本地合成原件-13800138000".getBytes(StandardCharsets.UTF_8);
        Files.write(original, content);

        VaultCrypto.encrypt(original, vault, "correct-passphrase".toCharArray());

        assertFalse(java.util.Arrays.equals(content, Files.readAllBytes(vault)));
        Path restored = temp.resolve("restored.bin");
        VaultCrypto.decrypt(vault, restored, "correct-passphrase".toCharArray());
        assertArrayEquals(content, Files.readAllBytes(restored));
        assertThrows(Exception.class,
                () -> VaultCrypto.decrypt(vault, temp.resolve("wrong.bin"), "wrong-passphrase".toCharArray()));
    }

    @Test
    void deletesPartialVaultWhenSourceReadFails() throws Exception {
        Path unreadableSource = Files.createDirectory(temp.resolve("source-directory"));
        Path vault = temp.resolve("partial/original.drenc");

        assertThrows(IOException.class,
                () -> VaultCrypto.encrypt(unreadableSource, vault, "correct-passphrase".toCharArray()));

        assertFalse(Files.exists(vault));
    }
}

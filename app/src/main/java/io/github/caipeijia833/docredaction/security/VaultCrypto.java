/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.security;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

public final class VaultCrypto {
    private static final byte[] MAGIC = "DRVAULT1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] AAD = "doc-redaction-vault/v1".getBytes(StandardCharsets.US_ASCII);
    private static final int ITERATIONS = 600_000;
    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 12;
    private static final int KEY_BITS = 256;
    private static final int GCM_TAG_BITS = 128;

    private VaultCrypto() {
    }

    public static void encrypt(Path source, Path vault, char[] passphrase) throws IOException {
        validatePassphrase(passphrase);
        byte[] salt = new byte[SALT_LENGTH];
        byte[] iv = new byte[IV_LENGTH];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);
        random.nextBytes(iv);
        byte[] keyBytes = null;
        try {
            keyBytes = deriveKey(passphrase, salt, ITERATIONS);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, keyBytes, iv);
            cipher.updateAAD(AAD);
            Files.createDirectories(vault.getParent());
            try (OutputStream fileOutput = Files.newOutputStream(vault,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                 DataOutputStream header = new DataOutputStream(fileOutput)) {
                header.write(MAGIC);
                header.writeInt(ITERATIONS);
                header.writeByte(SALT_LENGTH);
                header.writeByte(IV_LENGTH);
                header.write(salt);
                header.write(iv);
                header.flush();
                try (InputStream input = Files.newInputStream(source);
                     CipherOutputStream encrypted = new CipherOutputStream(fileOutput, cipher)) {
                    input.transferTo(encrypted);
                }
            }
        } catch (IOException ex) {
            Files.deleteIfExists(vault);
            throw ex;
        } catch (GeneralSecurityException ex) {
            Files.deleteIfExists(vault);
            throw new IOException("无法初始化本地加密库", ex);
        } finally {
            Arrays.fill(passphrase, '\0');
            if (keyBytes != null) {
                Arrays.fill(keyBytes, (byte) 0);
            }
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(iv, (byte) 0);
        }
    }

    public static void decrypt(Path vault, Path target, char[] passphrase) throws IOException {
        validatePassphrase(passphrase);
        byte[] keyBytes = null;
        byte[] salt = null;
        byte[] iv = null;
        try (InputStream fileInput = Files.newInputStream(vault); DataInputStream header = new DataInputStream(fileInput)) {
            byte[] magic = header.readNBytes(MAGIC.length);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new IOException("还原库格式无效");
            }
            int iterations = header.readInt();
            int saltLength = header.readUnsignedByte();
            int ivLength = header.readUnsignedByte();
            if (iterations < 100_000 || iterations > 2_000_000
                    || saltLength != SALT_LENGTH || ivLength != IV_LENGTH) {
                throw new IOException("还原库参数无效");
            }
            salt = header.readNBytes(saltLength);
            iv = header.readNBytes(ivLength);
            if (salt.length != saltLength || iv.length != ivLength) {
                throw new IOException("还原库已截断");
            }
            keyBytes = deriveKey(passphrase, salt, iterations);
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, keyBytes, iv);
            cipher.updateAAD(AAD);
            try (CipherInputStream decrypted = new CipherInputStream(fileInput, cipher);
                 OutputStream output = Files.newOutputStream(target,
                         StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                decrypted.transferTo(output);
            } catch (IOException ex) {
                Files.deleteIfExists(target);
                throw new IOException("口令错误或加密原件已损坏", ex);
            }
        } catch (GeneralSecurityException ex) {
            Files.deleteIfExists(target);
            throw new IOException("无法打开本地加密库", ex);
        } finally {
            Arrays.fill(passphrase, '\0');
            if (keyBytes != null) {
                Arrays.fill(keyBytes, (byte) 0);
            }
            if (salt != null) {
                Arrays.fill(salt, (byte) 0);
            }
            if (iv != null) {
                Arrays.fill(iv, (byte) 0);
            }
        }
    }

    private static byte[] deriveKey(char[] passphrase, byte[] salt, int iterations)
            throws GeneralSecurityException {
        PBEKeySpec specification = new PBEKeySpec(passphrase, salt, iterations, KEY_BITS);
        try {
            SecretKey generated = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(specification);
            return generated.getEncoded();
        } finally {
            specification.clearPassword();
        }
    }

    private static Cipher cipher(int mode, byte[] keyBytes, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher;
    }

    private static void validatePassphrase(char[] passphrase) {
        if (passphrase == null || passphrase.length < 10) {
            throw new IllegalArgumentException("可还原模式口令至少10个字符");
        }
        if (passphrase.length > 256) {
            throw new IllegalArgumentException("可还原模式口令不能超过256个字符");
        }
    }
}

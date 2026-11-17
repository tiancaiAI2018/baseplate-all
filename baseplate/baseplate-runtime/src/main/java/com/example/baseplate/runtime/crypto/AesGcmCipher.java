package com.example.baseplate.runtime.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 配置值加解密：JDK 原生 JCA，零第三方依赖。
 * 密文格式 ENC(v1:base64(salt|iv|ciphertext|tag))。
 */
public final class AesGcmCipher {

    public static final String PREFIX = "ENC(";
    public static final String VERSION = "v1";

    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 12;
    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private AesGcmCipher() {
    }

    public static String encrypt(String plain, String passphrase) {
        if (passphrase == null || passphrase.isBlank()) {
            throw new IllegalArgumentException("加密需要密钥，但未提供");
        }
        try {
            byte[] salt = randomBytes(SALT_LEN);
            byte[] iv = randomBytes(IV_LEN);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[SALT_LEN + IV_LEN + ciphertext.length];
            System.arraycopy(salt, 0, payload, 0, SALT_LEN);
            System.arraycopy(iv, 0, payload, SALT_LEN, IV_LEN);
            System.arraycopy(ciphertext, 0, payload, SALT_LEN + IV_LEN, ciphertext.length);
            return PREFIX + VERSION + ":" + Base64.getEncoder().encodeToString(payload) + ")";
        } catch (Exception e) {
            throw new IllegalStateException("配置加密失败", e);
        }
    }

    public static String decryptIfNeeded(String keyName, String value, String passphrase) {
        if (value == null || !isEncrypted(value)) {
            return value;
        }
        if (passphrase == null || passphrase.isBlank()) {
            throw new IllegalStateException("配置项 [" + keyName + "] 是密文但缺少密钥，"
                    + "请设置 BASEPLATE_CRYPTO_KEY 或 -Dbaseplate.crypto.key");
        }
        try {
            String inner = value.substring(PREFIX.length(), value.length() - 1);
            int colon = inner.indexOf(':');
            if (colon < 0 || !VERSION.equals(inner.substring(0, colon))) {
                throw new IllegalStateException("配置项 [" + keyName + "] 的密文版本不受支持");
            }
            byte[] payload = Base64.getDecoder().decode(inner.substring(colon + 1));
            if (payload.length <= SALT_LEN + IV_LEN + TAG_BITS / 8) {
                throw new IllegalStateException("配置项 [" + keyName + "] 的密文长度不合法");
            }
            byte[] salt = new byte[SALT_LEN];
            byte[] iv = new byte[IV_LEN];
            byte[] ciphertext = new byte[payload.length - SALT_LEN - IV_LEN];
            System.arraycopy(payload, 0, salt, 0, SALT_LEN);
            System.arraycopy(payload, SALT_LEN, iv, 0, IV_LEN);
            System.arraycopy(payload, SALT_LEN + IV_LEN, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("配置项 [" + keyName + "] 解密失败：密钥不匹配或密文被篡改", e);
        }
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX) && value.endsWith(")");
    }

    private static byte[] randomBytes(int len) {
        byte[] bytes = new byte[len];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static SecretKeySpec deriveKey(String passphrase, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_BITS);
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(key, "AES");
        } finally {
            spec.clearPassword();
        }
    }
}

package com.example.baseplate.platform.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 数据库内密钥加密：AES/GCM + PBKDF2 派生，密文带版本前缀便于轮换。
 * 主密钥来自环境变量，数据库被拖走也无法还原 client_secret。
 */
public final class SecretCipher {

    private static final String PREFIX = "v1:";
    private static final byte[] SALT = "baseplate-open-secret".getBytes(StandardCharsets.UTF_8);
    private static final int ITERATIONS = 120_000;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(String keyMaterial) {
        try {
            PBEKeySpec spec = new PBEKeySpec(keyMaterial.toCharArray(), SALT, ITERATIONS, 256);
            byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            this.key = new SecretKeySpec(derived, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("密钥派生失败", e);
        }
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("密钥加密失败", e);
        }
    }

    public String decrypt(String encrypted) {
        try {
            String body = encrypted.startsWith(PREFIX) ? encrypted.substring(PREFIX.length()) : encrypted;
            byte[] combined = Base64.getDecoder().decode(body);
            byte[] iv = new byte[12];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] plain = cipher.doFinal(combined, iv.length, combined.length - iv.length);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("密钥解密失败", e);
        }
    }
}

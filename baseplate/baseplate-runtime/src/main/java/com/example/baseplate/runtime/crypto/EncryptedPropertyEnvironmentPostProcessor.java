package com.example.baseplate.runtime.crypto;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Boot 4 配置解密扩展点。注册在 META-INF/spring.factories，key 必须是
 * org.springframework.boot.EnvironmentPostProcessor（Boot 4 新包名）。
 *
 * <p>两个实测约束：order 必须是 LOWEST_PRECEDENCE（ConfigData EPP 占用
 * HIGHEST+10）；只包装含密文的源，不能碰 ConfigurationPropertySourcesPropertySource。
 */
public class EncryptedPropertyEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String ENV_KEY = "BASEPLATE_CRYPTO_KEY";
    private static final String JVM_KEY = "baseplate.crypto.key";
    private static final String INTERNAL_SOURCE =
            "org.springframework.boot.context.properties.source.ConfigurationPropertySourcesPropertySource";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String passphrase = resolvePassphrase();
        MutablePropertySources sources = environment.getPropertySources();

        List<PropertySource<?>> snapshot = new ArrayList<>();
        sources.forEach(snapshot::add);

        List<String> cipherKeys = new ArrayList<>();
        Map<String, PropertySource<?>> toWrap = new LinkedHashMap<>();
        for (PropertySource<?> propertySource : snapshot) {
            if (!(propertySource instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            if (INTERNAL_SOURCE.equals(propertySource.getClass().getName())) {
                continue;
            }
            boolean containsCipher = false;
            for (String name : enumerable.getPropertyNames()) {
                Object value = enumerable.getProperty(name);
                if (value instanceof String text && AesGcmCipher.isEncrypted(text)) {
                    cipherKeys.add(name);
                    containsCipher = true;
                }
            }
            if (containsCipher) {
                toWrap.put(propertySource.getName(), propertySource);
            }
        }

        if (cipherKeys.isEmpty()) {
            return;
        }
        if (passphrase == null || passphrase.isBlank()) {
            throw new IllegalStateException("检测到 " + cipherKeys.size() + " 个加密配置项 ["
                    + String.join(", ", cipherKeys) + "] 但缺少密钥；请设置环境变量 " + ENV_KEY
                    + " 或 JVM 参数 -D" + JVM_KEY);
        }
        toWrap.forEach((name, original) ->
                sources.replace(name, new DecryptingPropertySource(original, passphrase)));
    }

    private String resolvePassphrase() {
        String fromEnv = System.getenv(ENV_KEY);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        String fromJvm = System.getProperty(JVM_KEY);
        return (fromJvm == null || fromJvm.isBlank()) ? null : fromJvm;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}

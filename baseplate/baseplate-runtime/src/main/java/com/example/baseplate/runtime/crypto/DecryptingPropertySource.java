package com.example.baseplate.runtime.crypto;

import org.springframework.core.env.PropertySource;

/** PropertySource 装饰器：读取时按 ENC( 前缀懒解密。 */
public final class DecryptingPropertySource extends PropertySource<Object> {

    private final PropertySource<?> delegate;
    private final String passphrase;

    public DecryptingPropertySource(PropertySource<?> delegate, String passphrase) {
        super(delegate.getName(), delegate.getSource());
        this.delegate = delegate;
        this.passphrase = passphrase;
    }

    @Override
    public Object getProperty(String name) {
        Object value = delegate.getProperty(name);
        if (value instanceof String text) {
            return AesGcmCipher.decryptIfNeeded(name, text, passphrase);
        }
        return value;
    }

    @Override
    public boolean containsProperty(String name) {
        return delegate.containsProperty(name);
    }
}

package com.example.baseplate.runtime.port;

/** 同一端口出现多个 Bean：必须启动失败，禁止静默选一个。 */
public class AmbiguousPortBindingException extends IllegalStateException {

    public AmbiguousPortBindingException(String portType, String[] beanNames) {
        super("端口 " + portType + " 存在多个实现: " + String.join(", ", beanNames)
                + "；请用条件装配保证恰好一个");
    }
}

package com.example.baseplate.platform.domain;

import com.example.baseplate.api.model.OpenApiSubject;

/**
 * 可对外开放的 API 定义：应用只能调用被授权的条目。
 *
 * <p>{@code subject} / {@code requiredPermission} / {@code requiredScope} 是安全边界，
 * 由 {@code @OpenApi} 注解声明，控制台只读——否则运营在页面上把 USER 改成 CLIENT，
 * 就等于把「必须真实用户」的业务接口开放给机器身份。</p>
 */
public record OpenApiDef(String code, String name, String method, String path,
                         String groupName, String description,
                         OpenApiSubject subject, String requiredPermission,
                         String requiredScope, String source, String handler,
                         boolean sandboxAllowed, boolean labelEdited, boolean enabled) {

    /** 兼容调用：只给路径与展示信息时按机器身份处理。 */
    public OpenApiDef(String code, String name, String method, String path,
                      String groupName, String description) {
        this(code, name, method, path, groupName, description,
                OpenApiSubject.CLIENT, null, null, "ANNOTATION", null, true, false, true);
    }

    /** 该接口是否接受机器身份（client_credentials / 纯签名）。 */
    public boolean clientAllowed() {
        return subject == OpenApiSubject.CLIENT || subject == OpenApiSubject.ANY;
    }

    /** 该接口是否接受用户身份（authorization_code 换来的令牌）。 */
    public boolean userAllowed() {
        return subject == OpenApiSubject.USER || subject == OpenApiSubject.ANY;
    }

    /**
     * 是否强制要求令牌。只有「用户主体」才无条件要令牌——
     * 纯签名模式（APPKEY_HMAC）本来就靠签名证明应用身份，没有令牌也就没有 scope 概念，
     * 硬要令牌会把只签名的接入方全部挡死。
     */
    public boolean requiresToken() {
        return subject == OpenApiSubject.USER;
    }
}

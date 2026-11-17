package com.example.baseplate.api.model;

/**
 * 开放接口允许的调用主体。
 *
 * <p>这是开放平台最容易出事的地方：机器身份（client_credentials）拿到的令牌只代表应用，
 * 代表不了任何自然人。业务接口如果内部要读「谁在操作」，就必须要求用户主体，
 * 否则要么拿到 null，要么把匿名请求当成合法调用。</p>
 */
public enum OpenApiSubject {

    /** 只需要应用身份：client_credentials 令牌或纯 AppKey 签名均可。 */
    CLIENT,

    /** 必须代表真实用户：只能是 authorization_code 换来的令牌，且令牌里带 uid。 */
    USER,

    /** 两者皆可；带用户身份时按用户处理，否则按应用处理。 */
    ANY
}

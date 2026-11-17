package com.example.baseplate.api.annotation;

import com.example.baseplate.api.model.OpenApiSubject;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 把一个业务接口暴露到开放平台：启动期扫描到即自动进入 API 目录，控制台直接可授权。
 *
 * <p>用法与约束：</p>
 * <ul>
 *   <li>只能标在 {@code /openapi/**} 前缀的处理器方法上。路径不符会在<b>启动期直接失败</b>，
 *       避免有人顺手标在内部 {@code /api/...} 上把管理接口暴露出去。</li>
 *   <li>一个方法只能对应一个路径 + 一个 HTTP 方法（{@code code} 是授权主键，不能一对多）。</li>
 *   <li>{@code method}、{@code path}、{@code subject}、{@code permission} 属于安全边界，
 *       控制台只读，只能停用不能改；{@code name}、{@code group}、{@code description}
 *       允许人工改名，且下次启动不会被清单覆盖。</li>
 * </ul>
 *
 * <pre>{@code
 * @GetMapping("/{orderNo}")
 * @OpenApi(code = "order.get", name = "查询订单", group = "订单",
 *          subject = OpenApiSubject.CLIENT, scope = "read")
 * public OrderView get(@PathVariable String orderNo) { ... }
 *
 * @PostMapping
 * @OpenApi(code = "order.create", name = "代客创建订单", group = "订单",
 *          subject = OpenApiSubject.USER, permission = "order:create", scope = "write")
 * public IdView create(@RequestBody CreateOrder cmd) {
 *     // 这里拿到的是被代理的真实用户，业务代码不知道自己在开放链路上
 *     String operator = currentUser.current().username();
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OpenApi {

    /**
     * 目录唯一编码，授权按它存。留空则按「类简名.方法名」自动生成。
     * 显式写死更稳：改名或挪类不会让已发出的授权失效。
     */
    String code() default "";

    /** 中文名，控制台与授权页展示。 */
    String name();

    /** 分组；留空时归入模块默认分组。 */
    String group() default "";

    String description() default "";

    /** 允许的调用主体，默认只允许机器身份。 */
    OpenApiSubject subject() default OpenApiSubject.CLIENT;

    /** 需要的 scope（如 read / write）；留空时沿用「非 GET 强制 write」的兜底规则。 */
    String scope() default "";

    /**
     * 当令牌带用户身份时，额外要求该用户拥有的权限点。
     * 与页面上的权限体系是同一份数据，不是「应用被授权即可」。
     */
    String permission() default "";

    /** 该接口是否允许沙盒调用；默认允许。 */
    boolean sandbox() default true;
}

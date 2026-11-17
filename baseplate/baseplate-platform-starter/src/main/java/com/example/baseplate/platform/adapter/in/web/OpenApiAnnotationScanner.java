package com.example.baseplate.platform.adapter.in.web;

import com.example.baseplate.api.annotation.OpenApi;
import com.example.baseplate.api.model.OpenApiSubject;
import com.example.baseplate.platform.domain.OpenApiDef;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 启动期扫描 {@code @OpenApi}，把注解变成 API 目录条目。
 *
 * <p>用 {@link RequestMappingHandlerMapping} 而不是自己反射找注解：只有在它那里
 * 才拿得到「类级 @RequestMapping 前缀 + 方法级路径」拼好的最终路径，
 * 也才拿得到条件匹配后的真实 HTTP 方法。自己拼一定会漏。</p>
 *
 * <p>这里的校验都是硬失败：路径不在 {@code /openapi/} 下、一个方法映射多个路径或多种方法、
 * 编码重复——这些错误留到运行期就是接口裸奔或授权串味，宁可起不来。</p>
 */
public final class OpenApiAnnotationScanner {

    private static final String OPEN_PREFIX = "/openapi/";
    private static final String CONTROLLER_SUFFIX = "Controller";

    private final List<RequestMappingHandlerMapping> mappings;

    public OpenApiAnnotationScanner(List<RequestMappingHandlerMapping> mappings) {
        this.mappings = mappings;
    }

    public List<OpenApiDef> scan() {
        Map<String, OpenApiDef> found = new LinkedHashMap<>();
        for (RequestMappingHandlerMapping mapping : mappings) {
            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry
                    : mapping.getHandlerMethods().entrySet()) {
                HandlerMethod handler = entry.getValue();
                OpenApi api = handler.getMethodAnnotation(OpenApi.class);
                if (api == null) {
                    continue;
                }
                OpenApiDef def = describe(entry.getKey(), handler, api);
                OpenApiDef exists = found.putIfAbsent(def.code(), def);
                if (exists != null) {
                    throw new IllegalStateException("@OpenApi 编码重复：" + def.code()
                            + "，" + exists.handler() + " 与 " + def.handler() + " 冲突");
                }
            }
        }
        return List.copyOf(found.values());
    }

    private OpenApiDef describe(RequestMappingInfo info, HandlerMethod handler, OpenApi api) {
        String where = handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName();
        Set<String> patterns = pathPatterns(info);
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
        if (patterns.size() != 1) {
            throw new IllegalStateException("@OpenApi 方法必须只映射一个路径：" + where
                    + " 实际 " + patterns + "（code 是授权主键，不能一对多）");
        }
        if (methods.size() != 1) {
            throw new IllegalStateException("@OpenApi 方法必须显式声明一种 HTTP 方法：" + where
                    + " 实际 " + methods);
        }
        String path = patterns.iterator().next();
        if (!path.startsWith(OPEN_PREFIX)) {
            throw new IllegalStateException("@OpenApi 只能标在 " + OPEN_PREFIX
                    + "** 的接口上，拒绝暴露：" + where + " → " + path);
        }
        String code = api.code().isBlank() ? deriveCode(handler) : api.code().trim();
        if (!code.matches("[A-Za-z0-9._:-]{3,64}")) {
            throw new IllegalStateException("@OpenApi 编码格式不合法（3-64 位字母数字 . _ : -）："
                    + code + " @ " + where);
        }
        String group = api.group().isBlank() ? deriveGroup(handler) : api.group().trim();
        String permission = api.permission().isBlank() ? null : api.permission().trim();
        OpenApiSubject subject = api.subject();
        if (subject == OpenApiSubject.USER && permission == null) {
            // 用户主体却不校验用户权限，等于把一切交给「应用被授权」——允许，但必须留痕
            org.slf4j.LoggerFactory.getLogger(OpenApiAnnotationScanner.class).warn(
                    "@OpenApi({}) 声明了用户主体但没写 permission，将只校验应用授权", code);
        }
        return new OpenApiDef(code, api.name().trim(), methods.iterator().next().name(), path,
                group, api.description().isBlank() ? null : api.description().trim(),
                subject, permission,
                api.scope().isBlank() ? null : api.scope().trim(),
                "ANNOTATION", where, api.sandbox(), false, true);
    }

    private Set<String> pathPatterns(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            return info.getPathPatternsCondition().getPatternValues();
        }
        if (info.getPatternsCondition() != null) {
            return info.getPatternsCondition().getPatterns();
        }
        return Set.of();
    }

    /** 类简名去 Controller 后缀 + 方法名，例如 OpenApiController#ping → openapi.ping。 */
    private String deriveCode(HandlerMethod handler) {
        String simple = handler.getBeanType().getSimpleName();
        if (simple.endsWith(CONTROLLER_SUFFIX) && simple.length() > CONTROLLER_SUFFIX.length()) {
            simple = simple.substring(0, simple.length() - CONTROLLER_SUFFIX.length());
        }
        return simple.toLowerCase(Locale.ROOT) + "." + handler.getMethod().getName();
    }

    private String deriveGroup(HandlerMethod handler) {
        String simple = handler.getBeanType().getSimpleName();
        if (simple.endsWith(CONTROLLER_SUFFIX) && simple.length() > CONTROLLER_SUFFIX.length()) {
            simple = simple.substring(0, simple.length() - CONTROLLER_SUFFIX.length());
        }
        return simple;
    }
}

package com.example.baseplate.audit.application;

import com.example.baseplate.api.annotation.AuditLog;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.port.out.CurrentUserProvider;
import com.example.baseplate.security.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务审计切面：记录动作、资源、操作人、结果。
 * 参数里的 password/secret/token 一律脱敏，审计日志不能成为泄密源。
 */
@Aspect
public class AuditAspect {

    private final AuditWriter writer;
    private final CurrentUserProvider currentUser;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuditAspect(AuditWriter writer, CurrentUserProvider currentUser) {
        this.writer = writer;
        this.currentUser = currentUser;
    }

    @Around("@annotation(com.example.baseplate.api.annotation.AuditLog)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        AuditLog annotation = signature.getMethod().getAnnotation(AuditLog.class);
        String action = annotation.action().isBlank()
                ? signature.getMethod().getName() : annotation.action();
        String resource = annotation.resource().isBlank()
                ? signature.getDeclaringType().getSimpleName() : annotation.resource();
        String before = toJson(joinPoint.getArgs());
        String resultCode = "OK";
        try {
            return joinPoint.proceed();
        } catch (Throwable e) {
            resultCode = "ERROR";
            throw e;
        } finally {
            CurrentUser user = safeUser();
            writer.business(user == null ? null : user.tenantId(), traceId(),
                    user == null ? "anonymous" : user.username(), action, resource, before,
                    "{\"result\":\"" + resultCode + "\"}");
        }
    }

    private CurrentUser safeUser() {
        try {
            return currentUser.current();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String traceId() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        Object traceId = request.getAttribute(TraceIdFilter.ATTR);
        return traceId == null ? null : String.valueOf(traceId);
    }

    private String toJson(Object[] args) {
        try {
            List<Object> sanitized = new ArrayList<>();
            for (Object arg : args) {
                sanitized.add(sanitize(arg));
            }
            return objectMapper.writeValueAsString(sanitized);
        } catch (RuntimeException e) {
            return "[]";
        }
    }

    private Object sanitize(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String text) {
            return text.length() > 500 ? text.substring(0, 500) + "..." : text;
        }
        try {
            Map<?, ?> map = objectMapper.convertValue(value, Map.class);
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key).toLowerCase();
                if (name.contains("password") || name.contains("secret")
                        || name.contains("token") || name.contains("credential")) {
                    result.put(String.valueOf(key), "***");
                } else {
                    result.put(String.valueOf(key), item);
                }
            });
            return result;
        } catch (RuntimeException e) {
            return String.valueOf(value);
        }
    }
}

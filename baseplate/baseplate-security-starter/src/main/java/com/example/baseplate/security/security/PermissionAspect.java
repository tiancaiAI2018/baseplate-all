package com.example.baseplate.security.security;

import com.example.baseplate.api.annotation.RequiresPermission;
import com.example.baseplate.api.exception.AuthenticationException;
import com.example.baseplate.api.exception.ForbiddenException;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.port.out.CurrentUserProvider;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;

import java.util.Arrays;

/**
 * 权限点校验：超级管理员短路，其余按令牌里的 permissions 判断。
 * 权限码由模块清单注册，不在代码里维护第二份清单。
 */
@Aspect
public class PermissionAspect {

    private static final String SUPER_ROLE = "SUPER_ADMIN";
    private final CurrentUserProvider currentUser;

    public PermissionAspect(CurrentUserProvider currentUser) {
        this.currentUser = currentUser;
    }

    @Around("@annotation(com.example.baseplate.api.annotation.RequiresPermission) "
            + "|| @within(com.example.baseplate.api.annotation.RequiresPermission)")
    public Object check(ProceedingJoinPoint joinPoint) throws Throwable {
        RequiresPermission annotation = resolve(joinPoint);
        if (annotation == null) {
            return joinPoint.proceed();
        }
        CurrentUser user;
        try {
            user = currentUser.current();
        } catch (AuthenticationException e) {
            throw new ForbiddenException("未认证");
        }
        if (user.roles().contains(SUPER_ROLE)) {
            return joinPoint.proceed();
        }
        boolean allowed = Arrays.stream(annotation.value())
                .anyMatch(permission -> user.permissions().contains(permission));
        if (!allowed) {
            throw new ForbiddenException("缺少权限：" + String.join(",", annotation.value()));
        }
        return joinPoint.proceed();
    }

    private RequiresPermission resolve(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        RequiresPermission annotation = signature.getMethod().getAnnotation(RequiresPermission.class);
        if (annotation != null) {
            return annotation;
        }
        return joinPoint.getTarget().getClass().getAnnotation(RequiresPermission.class);
    }
}

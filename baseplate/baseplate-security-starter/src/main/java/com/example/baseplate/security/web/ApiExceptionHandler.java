package com.example.baseplate.security.web;

import com.example.baseplate.api.exception.BaseplateException;
import com.example.baseplate.api.exception.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 统一错误体：RFC 9457 ProblemDetail，禁止把堆栈或 SQL 暴露给调用方。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BaseplateException.class)
    public ProblemDetail handleBaseplate(BaseplateException e, HttpServletRequest request) {
        HttpStatus status = switch (e.code()) {
            case "AUTH_FAILED" -> HttpStatus.UNAUTHORIZED;
            case "FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "CONFLICT" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return problem(status, e.code(), e.getMessage(), request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class})
    public ProblemDetail handleBadRequest(Exception e, HttpServletRequest request) {
        String detail = e instanceof MethodArgumentNotValidException validation
                ? validation.getBindingResult().getFieldErrors().stream()
                    .map(err -> err.getField() + ": " + err.getDefaultMessage())
                    .findFirst().orElse("参数不合法")
                : e.getMessage();
        return problem(HttpStatus.BAD_REQUEST, "BAD_REQUEST", detail, request);
    }

    /**
     * 请求体读不出来（JSON 语法错、字段类型不匹配、缺原始类型字段）是调用方的问题，
     * 不能落到 catch-all 变成 500——那会让接入方以为服务挂了，也看不到具体是哪个字段。
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(org.springframework.http.converter.HttpMessageNotReadableException e,
                                          HttpServletRequest request) {
        String detail = e.getMostSpecificCause().getMessage();
        if (detail == null || detail.isBlank()) {
            detail = "请求体不是合法 JSON";
        }
        detail = detail.length() > 180 ? detail.substring(0, 180) + "…" : detail;
        return problem(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "请求体解析失败：" + detail, request);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ProblemDetail handleForbidden(ForbiddenException e, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", e.getMessage(), request);
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ProblemDetail handleNotFound(Exception e, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "资源不存在", request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e, HttpServletRequest request) {
        String traceId = String.valueOf(request.getAttribute(TraceIdFilter.ATTR));
        log.error("未处理异常 traceId={}", traceId, e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误", request);
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        problem.setProperty("traceId", String.valueOf(request.getAttribute(TraceIdFilter.ATTR)));
        problem.setProperty("path", request.getRequestURI());
        return problem;
    }
}

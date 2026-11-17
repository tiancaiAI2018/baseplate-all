package com.example.baseplate.identity.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 密码策略：收在领域对象里，任何入口改密都绕不过去。
 * 默认中等复杂度：≥8 位、大小写 + 数字、不含用户名。
 */
public final class PasswordPolicy {

    private final int minLength;

    public PasswordPolicy(int minLength) {
        this.minLength = minLength;
    }

    public void validate(String rawPassword, String username) {
        List<String> errors = new ArrayList<>();
        if (rawPassword == null || rawPassword.length() < minLength) {
            errors.add("长度至少 " + minLength + " 位");
        }
        if (rawPassword != null) {
            if (!rawPassword.matches(".*[A-Z].*")) {
                errors.add("需包含大写字母");
            }
            if (!rawPassword.matches(".*[a-z].*")) {
                errors.add("需包含小写字母");
            }
            if (!rawPassword.matches(".*[0-9].*")) {
                errors.add("需包含数字");
            }
            if (username != null && !username.isBlank()
                    && rawPassword.toLowerCase().contains(username.toLowerCase())) {
                errors.add("不能包含用户名");
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("密码不符合策略：" + String.join("；", errors));
        }
    }
}

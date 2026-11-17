package com.example.baseplate.rule.domain;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/** 单条件：字段 + 运算符 + 值，没有表达式文本，因此没有表达式注入面。 */
public record RuleCondition(String field, Op op, Object value) {

    public boolean test(Map<String, Object> context) {
        Object actual = context.get(field);
        if (actual == null) {
            return false;
        }
        return switch (op) {
            case EQ -> Objects.equals(asString(actual), asString(value));
            case NE -> !Objects.equals(asString(actual), asString(value));
            case GT -> compare(actual, value) > 0;
            case GTE -> compare(actual, value) >= 0;
            case LT -> compare(actual, value) < 0;
            case LTE -> compare(actual, value) <= 0;
            case IN -> value instanceof Collection<?> collection
                    && collection.stream().map(RuleCondition::asString)
                    .anyMatch(item -> item.equals(asString(actual)));
            case CONTAINS -> asString(actual).contains(asString(value));
        };
    }

    private static int compare(Object left, Object right) {
        BigDecimal a = toDecimal(left);
        BigDecimal b = toDecimal(right);
        if (a != null && b != null) {
            return a.compareTo(b);
        }
        return asString(left).compareTo(asString(right));
    }

    private static BigDecimal toDecimal(Object value) {
        try {
            return value == null ? null : new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}

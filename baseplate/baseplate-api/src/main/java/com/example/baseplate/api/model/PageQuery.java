package com.example.baseplate.api.model;

/** 分页查询：page 从 1 开始，size 上限由适配器校验。 */
public record PageQuery(int page, int size, String sort) {
    public PageQuery {
        if (page < 1) {
            throw new IllegalArgumentException("page 必须 >= 1");
        }
        if (size < 1 || size > 500) {
            throw new IllegalArgumentException("size 必须在 1..500");
        }
    }
}

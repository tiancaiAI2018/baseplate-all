package com.example.baseplate.api.model;

import java.util.List;

/** 分页结果：total 必须是数据库真实总数，不是当前页条数。 */
public record PageResult<T>(List<T> items, long total) {
}

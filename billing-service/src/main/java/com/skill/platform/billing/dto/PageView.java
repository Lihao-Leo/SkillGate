package com.skill.platform.billing.dto;

import java.util.List;

/**
 * 通用分页视图。
 */
public record PageView<T>(long total, long pageNo, long pageSize, List<T> items) {
}

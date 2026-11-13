package com.hms.shared;

import java.util.List;

/** api-design.md section 1 pagination envelope. */
public record PageResult<T>(List<T> items, int page, int size, long totalItems) {
}

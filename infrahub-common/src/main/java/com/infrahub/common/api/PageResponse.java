package com.infrahub.common.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> {

    private long pageNum;
    private long pageSize;
    private long total;
    private List<T> records;

    public static <T> PageResponse<T> from(IPage<T> page) {
        return PageResponse.<T>builder()
                .pageNum(page.getCurrent())
                .pageSize(page.getSize())
                .total(page.getTotal())
                .records(page.getRecords())
                .build();
    }

    public static <T> PageResponse<T> empty(PageQuery query) {
        return PageResponse.<T>builder()
                .pageNum(query.getPageNum())
                .pageSize(query.getPageSize())
                .total(0)
                .records(Collections.emptyList())
                .build();
    }
}

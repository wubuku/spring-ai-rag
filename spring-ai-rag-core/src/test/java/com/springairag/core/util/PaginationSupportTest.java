package com.springairag.core.util;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一分页参数收敛的回归测试。
 *
 * <p>历史上 offset/limit 与 page/size 换算不一致时，第 2 页会重复返回第 1 页；
 * 这里锁住"offset 对齐页边界且回显一致"的契约。
 */
class PaginationSupportTest {

    @Test
    void offsetLimitMapsSecondPageToPageOne() {
        Pageable pageable = PaginationSupport.offsetLimit(20, 20, PaginationSupport.NEWEST_FIRST);

        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getOffset()).isEqualTo(20);
        assertThat(pageable.getPageSize()).isEqualTo(20);
    }

    @Test
    void offsetLimitAlignsUnalignedOffsetDownToPageBoundary() {
        Pageable pageable = PaginationSupport.offsetLimit(25, 20, PaginationSupport.NEWEST_FIRST);

        // 25 不是 20 的整数倍；回显 offset 必须是实际生效的页边界，不能仍说 25 却返回第 0 页。
        assertThat(pageable.getOffset()).isEqualTo(20);
        assertThat(pageable.getPageNumber()).isEqualTo(1);
    }

    @Test
    void negativeOrZeroValuesAreClamped() {
        Pageable pageable = PaginationSupport.offsetLimit(-5, 0, PaginationSupport.NEWEST_FIRST);

        assertThat(pageable.getOffset()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(1);
    }

    @Test
    void oversizedLimitIsCappedAtDefaultMax() {
        Pageable pageable = PaginationSupport.offsetLimit(0, 100_000, PaginationSupport.NEWEST_FIRST);

        assertThat(pageable.getPageSize()).isEqualTo(PaginationSupport.DEFAULT_MAX_PAGE_SIZE);
    }

    @Test
    void pageSizeIsClampedAndSortedWithTieBreak() {
        Pageable pageable = PaginationSupport.pageSize(-1, 10_000, PaginationSupport.NEWEST_FIRST);

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(PaginationSupport.DEFAULT_MAX_PAGE_SIZE);
        assertThat(pageable.getSort()).isEqualTo(PaginationSupport.NEWEST_FIRST);
    }

    @Test
    void newestFirstSortsByCreatedAtThenId() {
        assertThat(PaginationSupport.NEWEST_FIRST)
                .isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt")
                        .and(Sort.by(Sort.Direction.DESC, "id")));
    }
}

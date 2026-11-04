package com.springairag.core.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 列表端点的统一分页契约。
 *
 * <p>为什么需要集中处理：分页参数散落在各个 controller/service 里，重复实现
 * 必然漂移。之前出现过 {@code offset/limit} 与 {@code page/size} 的换算不一致，
 * 结果第 2 页一直返回第 1 页。这里把三件事固定成同一个实现：</p>
 *
 * <ol>
 *   <li>越界输入收敛而不是报错（负 offset、0/负 limit 都会变成合法值）；</li>
 *   <li>limit/size 有硬上限，避免一次请求把整张表拉进内存；</li>
 *   <li>offset 对齐到 limit 的整数倍，保证 {@code page = offset / limit}
 *       与返回的 offset 一致。</li>
 * </ol>
 */
public final class PaginationSupport {

    /** 单页默认上限。列表接口返回的是完整记录，超过这个量级应当改用导出。 */
    public static final int DEFAULT_MAX_PAGE_SIZE = 200;

    /**
     * 常见列表的稳定排序：创建时间倒序 + 主键倒序。
     *
     * <p>只按 {@code createdAt} 排序时，同一时间戳的记录在跨页查询中顺序不稳定，
     * 同一条记录可能在第 1、2 页各出现一次，或者干脆被跳过。主键是唯一且单调的，
     * 作为 tie-break 能给出全序。</p>
     */
    public static final Sort NEWEST_FIRST = Sort
            .by(Sort.Direction.DESC, "createdAt")
            .and(Sort.by(Sort.Direction.DESC, "id"));

    private PaginationSupport() {
    }

    /** 负数页码按第 0 页处理。 */
    public static int page(int page) {
        return Math.max(page, 0);
    }

    /** size 收敛到 {@code [1, DEFAULT_MAX_PAGE_SIZE]}。 */
    public static int size(int size) {
        return size(size, DEFAULT_MAX_PAGE_SIZE);
    }

    /** size 收敛到 {@code [1, max]}。 */
    public static int size(int size, int max) {
        return Math.min(Math.max(size, 1), max);
    }

    /** 负数 offset 按 0 处理。 */
    public static int offset(int offset) {
        return Math.max(offset, 0);
    }

    /** limit 收敛到 {@code [1, DEFAULT_MAX_PAGE_SIZE]}。 */
    public static int limit(int limit) {
        return limit(limit, DEFAULT_MAX_PAGE_SIZE);
    }

    /** limit 收敛到 {@code [1, max]}。 */
    public static int limit(int limit, int max) {
        return Math.min(Math.max(limit, 1), max);
    }

    /**
     * 把 offset/limit 转成 Spring Data 的 {@link Pageable}。
     *
     * <p>offset 不是 limit 的整数倍时向下对齐到页边界；调用方应使用
     * {@link Pageable#getOffset()} 回显 offset，保证"请求的 offset"与
     * "实际返回的 offset"一致。</p>
     */
    public static Pageable offsetLimit(int offset, int limit, Sort sort) {
        return offsetLimit(offset, limit, sort, DEFAULT_MAX_PAGE_SIZE);
    }

    public static Pageable offsetLimit(int offset, int limit, Sort sort, int maxLimit) {
        int safeLimit = limit(limit, maxLimit);
        int safeOffset = offset(offset);
        return PageRequest.of(safeOffset / safeLimit, safeLimit, sort);
    }

    /** 把 page/size 转成 {@link Pageable}。 */
    public static Pageable pageSize(int page, int size, Sort sort) {
        return pageSize(page, size, sort, DEFAULT_MAX_PAGE_SIZE);
    }

    public static Pageable pageSize(int page, int size, Sort sort, int maxSize) {
        return PageRequest.of(page(page), size(size, maxSize), sort);
    }
}

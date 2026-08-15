-- V26: 删除未被主检索链读取的旧 Spring AI PgVectorStore 默认表。
-- 非空表必须先人工审计/导出，迁移拒绝自动丢数据。

DO $$
DECLARE
    existing_rows BIGINT;
BEGIN
    IF to_regclass('public.rag_vector_store') IS NULL THEN
        RAISE NOTICE 'rag_vector_store does not exist, cleanup skipped';
        RETURN;
    END IF;

    EXECUTE 'SELECT COUNT(*) FROM public.rag_vector_store' INTO existing_rows;
    IF existing_rows > 0 THEN
        RAISE EXCEPTION
            'Refusing to drop non-empty public.rag_vector_store (% rows). Audit/export it before retrying.',
            existing_rows;
    END IF;

    DROP TABLE public.rag_vector_store;
END
$$;

package com.springairag.core.retrieval.fulltext;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 关键词通道的公共查询打分片段。
 *
 * <p>PostgreSQL 全文分词会把连字符编号拆开：english 配置下 {@code NEBULA-300}
 * 变成 {@code nebula} + {@code -300}，jiebacfg 下变成 {@code nebula} + {@code 300}。
 * 仅靠词项无法区分 {@code NEBULA-300} 与 {@code NEBULA-400}，因此这里把查询中的
 * 编号提取出来，在 SQL 打分阶段按“原文子串命中”加权，让精确编号压过近邻编号。</p>
 *
 * <p>另外，OR 语义的 tsquery 由数据库侧对查询文本分词后拼装，避免 Java 侧重复实现
 * 中英文分词规则；拼装结果只含 {@code quote_literal} 包裹的词项，不会引入语法注入。</p>
 */
final class FulltextQuerySupport {

    /** 连字符/下划线/点号连接的编号或型号，如 NEBULA-300、RAG-4291、NEBULA-SDK-2.4.0。 */
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z][A-Za-z0-9]*(?:[-_.][A-Za-z0-9]+)+");

    /** 最多参与加权的编号数量，避免长查询把打分完全拖偏。 */
    private static final int MAX_IDENTIFIER_TERMS = 3;

    /** 精确编号子串命中一次的加权值；ts_rank_cd 典型值在 0.01–0.3，1.0 足以改变次序。 */
    private static final String IDENTIFIER_BOOST = "1.0";

    private static final double TITLE_WEIGHT = 0.35;

    private FulltextQuerySupport() {
    }

    static double titleWeight() {
        return TITLE_WEIGHT;
    }

    /**
     * 提取查询中的编号/型号词项；普通连字符单词（如 fine-grained）不参与加权。
     */
    static List<String> identifierTerms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Matcher matcher = IDENTIFIER.matcher(query);
        while (matcher.find() && terms.size() < MAX_IDENTIFIER_TERMS) {
            String token = matcher.group();
            // 只把“看起来像编号”的词项当作精确匹配目标：含数字或含大写字母。
            if (containsDigit(token) || containsUpperCase(token)) {
                terms.add(token);
            }
        }
        return List.copyOf(terms);
    }

    /**
     * 生成精确编号加权 SQL 片段；每个词项占一个 {@code ?} 参数。
     */
    static String identifierBoostSql(List<String> identifiers) {
        if (identifiers.isEmpty()) {
            return "";
        }
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < identifiers.size(); i++) {
            sql.append(" + CASE WHEN strpos(lower(e.chunk_text), lower(?)) > 0 THEN ")
                    .append(IDENTIFIER_BOOST).append(" ELSE 0 END");
        }
        return sql.toString();
    }

    /**
     * 数据库侧分词后拼装 OR tsquery 的标量子查询。
     *
     * <p>空词项（jieba 会把空格切成空词）被过滤；没有任何词项时聚合结果为 NULL，
     * {@code @@ NULL} 不命中任何行，等价于“关键词通道无候选”。</p>
     *
     * <p>写成标量子查询而不是先取回 Java 再拼串，可以保持单条 SQL：PostgreSQL 会把
     * 它作为 InitPlan 求值一次，WHERE 里仍是常量比较，GIN 索引可用。</p>
     */
    static String tsQueryLexemeSubquery(String tsConfig) {
        return "SELECT to_tsquery('" + tsConfig + "', "
                + "string_agg(quote_literal(lex), ' | ')) "
                + "FROM unnest(tsvector_to_array(to_tsvector('" + tsConfig + "', ?))) AS lex "
                + "WHERE btrim(lex) <> ''";
    }

    /**
     * 候选覆盖率下限：查询有 3 个以上词项时，片段至少命中一半（向上取整）的不同词项。
     *
     * <p>OR 语义会召回只命中少数通用词的片段；只要命中 2 个词就给关键词投票，反而会
     * 让“字面擦边”的退役/诱饵文档压过真正的答案（固定 goldenset 的 forbidden 用例
     * 实测踩到）。因此下限随查询词项数增长：{@code ceil(n/2)}，短查询（1–2 个词项）
     * 保持原语义，避免误杀。</p>
     *
     * <p>覆盖率过滤只作用在 GIN 已经筛出的候选行上，不会退化成全表扫描；
     * 片段返回 {@code AND} 开头的 SQL，占两个 {@code ?} 参数：查询词项集合与词项数量。</p>
     *
     * <p>精确编号是例外：查询里的 {@code NEBULA-300} 命中片段原文时直接放行，因为
     * 中文查询的其余词项（如“占几个机架单元”）不会出现在英文表格里，靠覆盖率会把
     * 唯一正确的编号片段全部挡掉。编号加权本身（{@link #identifierBoostSql}）仍会把
     * 精确命中排到近邻编号之上。每个编号在覆盖率参数之后各占一个 {@code ?} 参数。</p>
     */
    static String coverageFloorSql(
            String tsConfig, String docVectorExpression,
            String chunkTextExpression, List<String> identifiers) {
        String queryLexemes = "SELECT lex FROM unnest(tsvector_to_array(to_tsvector('"
                + tsConfig + "', ?))) AS lex WHERE btrim(lex) <> ''";
        String lexemeCount = "SELECT CASE WHEN count(*) >= 3 THEN (count(*) + 1) / 2 ELSE 1 END "
                + "FROM unnest(tsvector_to_array(to_tsvector('" + tsConfig + "', ?))) AS lex "
                + "WHERE btrim(lex) <> ''";
        StringBuilder sql = new StringBuilder("AND ((SELECT count(*) FROM unnest(tsvector_to_array(")
                .append(docVectorExpression).append(")) AS doc_lex WHERE doc_lex IN (")
                .append(queryLexemes).append(")) >= (").append(lexemeCount).append(")");
        for (String ignored : identifiers) {
            sql.append(" OR strpos(lower(").append(chunkTextExpression).append("), lower(?)) > 0");
        }
        return sql.append(") ").toString();
    }

    private static boolean containsDigit(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isDigit(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsUpperCase(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isUpperCase(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}

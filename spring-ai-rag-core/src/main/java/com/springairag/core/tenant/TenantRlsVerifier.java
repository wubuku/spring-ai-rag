package com.springairag.core.tenant;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WP3：RLS 启动 verifier（实施规划 §6 WP3）。
 *
 * <p>对齐参考项目 {@code ../seedance-research} 的 {@code StartupRlsVerifier} 语义：
 * 验「语义」不只验「数量」——必须拒绝 {@code USING(true)} 恒真 policy、必须双向比对
 * catalog 与数据库、必须在无 GUC 裸连接上读到 0 行（fail-closed 采样）。</p>
 *
 * <p>实现为接口 + 默认实现分离：{@link TenantModeGuard}（STRICT 分支）依赖本接口，
 * 负向集成测试可注入「校验失败」替身而不必构造整个数据库。</p>
 */
public interface TenantRlsVerifier {

    /** 执行全项校验；任一项失败抛 {@link IllegalStateException} 并拒绝启动。 */
    void verify();

    /** RLS 覆盖清单 = catalog 中 TENANT_PRIVATE 表（单一事实来源）。 */
    static List<String> rlsProtectedTables() {
        return TenantTableCatalog.entries().stream()
                .filter(e -> e.ownership() == TenantTableCatalog.TableOwnership.TENANT_PRIVATE)
                .map(e -> "spring_ai_chat_memory".equalsIgnoreCase(e.tableName())
                        ? "spring_ai_chat_memory" : e.tableName())
                .toList();
    }

    /**
     * 恒真表达式判定：字符串去空白后为 {@code true}（不区分大小写）即恒真。
     * 规范表达式 {@code tenant_id = current_setting('app.current_tenant_id', true)} 不匹配。
     */
    static boolean isPermissivePolicy(String qual) {
        return qual != null && qual.strip().equalsIgnoreCase("true");
    }

    /** 默认实现：直接对数据库执行全项校验。 */
    final class JdbcTenantRlsVerifier implements TenantRlsVerifier {

        private final JdbcTemplate jdbc;

        public JdbcTenantRlsVerifier(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public void verify() {
            List<String> failures = new ArrayList<>();
            verifyRole(failures);
            verifyRlsCoverage(failures);
            verifyPolicySemantics(failures);
            verifyNoGucReadsZeroRows(failures);
            if (!failures.isEmpty()) {
                throw new IllegalStateException("TenantRlsVerifier failed: " + String.join("; ", failures));
            }
        }

        /** 当前角色必须非 superuser、非 BYPASSRLS。 */
        private void verifyRole(List<String> failures) {
            Map<String, Object> role = jdbc.queryForMap("""
                    SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user
                    """);
            if (Boolean.TRUE.equals(role.get("rolsuper"))) {
                failures.add("current_user must not be superuser");
            }
            if (Boolean.TRUE.equals(role.get("rolbypassrls"))) {
                failures.add("current_user must have rolbypassrls=false");
            }
            // 宽松 platform 角色：app 不得继承任何 BYPASSRLS 角色（否则可 SET ROLE 绕过 RLS）。
            Boolean inheritsBypass = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_roles r
                        WHERE r.rolbypassrls
                          AND pg_has_role(current_user::regrole::oid, r.oid, 'MEMBER')
                          AND r.rolname <> current_user
                    )
                    """, Boolean.class);
            if (Boolean.TRUE.equals(inheritsBypass)) {
                failures.add("app 角色继承了 BYPASSRLS 角色（宽松 platform 角色），可绕过 RLS");
            }
            // 任何 BYPASSRLS 且被 app 可 SET ROLE 的 role 都视为宽松平台角色。
            Boolean roguePlatform = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_roles r
                        WHERE r.rolbypassrls AND has_database_privilege(current_user, current_database(), 'CONNECT')
                          AND pg_has_role(current_user::regrole::oid, r.oid, 'USAGE')
                    )
                    """, Boolean.class);
            if (Boolean.TRUE.equals(roguePlatform)) {
                failures.add("存在 app 可使用的 BYPASSRLS 平台角色（宽松 platform 角色）");
            }
        }

        /** catalog ↔ pg_class 双向一致：清单每张表 ENABLE+FORCE，且无未登记表启用 RLS。 */
        private void verifyRlsCoverage(List<String> failures) {
            for (String table : rlsProtectedTables()) {
                Map<String, Object> row;
                try {
                    row = jdbc.queryForMap("""
                            SELECT c.relrowsecurity, c.relforcerowsecurity
                            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                            WHERE n.nspname = 'public' AND lower(c.relname) = lower(?)
                              AND c.relkind = 'r'
                            """, table);
                } catch (org.springframework.dao.EmptyResultDataAccessException notPresent) {
                    failures.add("RLS 表缺失（catalog 已登记但库中不存在）: " + table);
                    continue;
                }
                if (!Boolean.TRUE.equals(row.get("relrowsecurity"))
                        || !Boolean.TRUE.equals(row.get("relforcerowsecurity")) ) {
                    failures.add("表 " + table + " 未 ENABLE+FORCE RLS");
                }
            }
            // 差集：库中启用 RLS 的表必须全部在清单内（防平台表误开 RLS）。
            List<Map<String, Object>> rlsTables = jdbc.queryForList("""
                    SELECT c.relname FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relrowsecurity
                    """);
            List<String> expected = rlsProtectedTables().stream()
                    .map(String::toLowerCase).toList();
            for (Map<String, Object> r : rlsTables) {
                String name = String.valueOf(r.get("relname")).toLowerCase();
                if (!expected.contains(name)) {
                    failures.add("未登记表 " + name + " 启用了 RLS");
                }
            }
        }

        /** policy 语义校验：每张 RLS 表必须有 tenant_isolation policy 且非恒真。 */
        private void verifyPolicySemantics(List<String> failures) {
            Map<String, List<Map<String, Object>>> policies = new LinkedHashMap<>();
            jdbc.queryForList("""
                    SELECT tablename, policyname, qual::text AS qual, with_check::text AS with_check
                    FROM pg_policies WHERE schemaname = 'public'
                    """).forEach(row -> policies
                    .computeIfAbsent(String.valueOf(row.get("tablename")), k -> new ArrayList<>())
                    .add(row));
            for (String table : rlsProtectedTables()) {
                String lower = table.toLowerCase();
                List<Map<String, Object>> rows = policies.getOrDefault(lower, List.of());
                if (rows.isEmpty()) {
                    failures.add("表 " + lower + " 缺 tenant_isolation policy");
                    continue;
                }
                Map<String, Object> p = rows.get(0);
                if (!"tenant_isolation".equals(p.get("policyname"))) {
                    failures.add("表 " + lower + " policy 名必须为 tenant_isolation");
                }
                if (isPermissivePolicy(String.valueOf(p.get("qual")))
                        || isPermissivePolicy(String.valueOf(p.get("with_check")))) {
                    failures.add("表 " + lower + " policy 表达式为恒真（USING/WITH CHECK = true）");
                }
                String qual = String.valueOf(p.get("qual"));
                if (!qual.contains("current_setting('app.current_tenant_id'")) {
                    failures.add("表 " + lower + " policy 未绑定 app.current_tenant_id GUC");
                }
            }
        }

        /** fail-closed 采样：RESET GUC 后 SELECT 租户表必须返回 0 行。 */
        private void verifyNoGucReadsZeroRows(List<String> failures) {
            jdbc.execute("RESET app.current_tenant_id");
            for (String table : List.of("rag_collection", "rag_documents", "rag_embeddings")) {
                Long count = jdbc.queryForObject(
                        "SELECT count(*) FROM " + table, Long.class);
                if (count != null && count > 0) {
                    failures.add("无 GUC 裸连接 SELECT " + table + " 返回 " + count + " 行（fail-closed 破坏）");
                }
            }
        }
    }
}

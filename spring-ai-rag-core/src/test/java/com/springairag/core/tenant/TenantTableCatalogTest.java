package com.springairag.core.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP1：租户表归属 catalog 完整性测试。
 *
 * 契约（实施者必须满足）：
 * <ul>
 *   <li>catalog 覆盖本仓迁移中全部存活的业务表（数量不写死，以迁移脚本实际存活集合为准）；</li>
 *   <li>每张表都必须有类别、理由、RLS 决策、平台池访问点；</li>
 *   <li>类别只能取 {@link TenantTableCatalog.TableOwnership} 中的枚举值；</li>
 *   <li>租户私有表必须启用 RLS 且声明 tenant-leading 索引；</li>
 *   <li>未登记的表必须能被 catalog 明确拒绝（防「新增表漏登记」）。</li>
 * </ul>
 */
@DisplayName("TenantTableCatalog Tests")
class TenantTableCatalogTest {

    @Test
    @DisplayName("catalog 覆盖全部存活业务表，且无重复登记")
    void catalogCoversAllLiveTables() {
        List<TenantTableCatalog.TableEntry> entries = TenantTableCatalog.entries();

        // 数量不写死：与迁移脚本存活表集合的一致性由 catalogMatchesMigrationCreateTableInventory 证明。
        assertFalse(entries.isEmpty(), "catalog 不得为空");

        Set<String> names = entries.stream()
                .map(TenantTableCatalog.TableEntry::tableName)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(entries.size(), names.size(), "catalog 中不得有重复表名");
    }

    @Test
    @DisplayName("每张表都登记了类别、理由、RLS 决策与平台池访问点")
    void everyTableHasCompleteDecision() {
        for (TenantTableCatalog.TableEntry entry : TenantTableCatalog.entries()) {
            assertNotNull(entry.tableName());
            assertFalse(entry.tableName().isBlank(), "表名不得为空");
            assertNotNull(entry.ownership(), entry.tableName() + " 缺少归属类别");
            assertFalse(entry.reason().isBlank(), entry.tableName() + " 缺少裁决理由");
            assertNotNull(entry.rlsEnabled(), entry.tableName() + " 缺少 RLS 决策");
            assertNotNull(entry.platformPoolAccess(), entry.tableName() + " 缺少平台池访问点");
        }
    }

    @Test
    @DisplayName("租户私有表启用 RLS 且声明 tenant-leading 索引")
    void tenantPrivateTablesRequireRlsAndTenantLeadingIndex() {
        List<TenantTableCatalog.TableEntry> tenantPrivate = TenantTableCatalog.entries().stream()
                .filter(e -> e.ownership() == TenantTableCatalog.TableOwnership.TENANT_PRIVATE)
                .toList();

        assertFalse(tenantPrivate.isEmpty(), "应至少存在租户私有表");
        for (TenantTableCatalog.TableEntry entry : tenantPrivate) {
            assertTrue(entry.rlsEnabled(), entry.tableName() + " 是租户私有表，必须启用 RLS");
            assertTrue(entry.tenantLeadingIndexRequired(),
                    entry.tableName() + " 是租户私有表，必须要求 tenant-leading 索引");
        }
    }

    @Test
    @DisplayName("平台全局表不启用 RLS，且不得声明为租户私有")
    void platformGlobalTablesDoNotEnableRls() {
        List<TenantTableCatalog.TableEntry> platformGlobal = TenantTableCatalog.entries().stream()
                .filter(e -> e.ownership() == TenantTableCatalog.TableOwnership.PLATFORM_GLOBAL)
                .toList();

        assertFalse(platformGlobal.isEmpty(), "应至少存在平台全局表");
        for (TenantTableCatalog.TableEntry entry : platformGlobal) {
            assertFalse(entry.rlsEnabled(), entry.tableName() + " 是平台全局表，不应启用 RLS");
        }
    }

    @Test
    @DisplayName("认证凭据表被显式标记为凭据类别")
    void credentialTablesAreExplicitlyClassified() {
        List<TenantTableCatalog.TableEntry> credentials = TenantTableCatalog.entries().stream()
                .filter(e -> e.ownership() == TenantTableCatalog.TableOwnership.CREDENTIAL)
                .toList();

        assertEquals(Set.of("rag_api_key", "rag_api_principal",
                        "rag_api_key_rotation", "rag_api_provisioning_operation"),
                credentials.stream().map(TenantTableCatalog.TableEntry::tableName)
                        .collect(java.util.stream.Collectors.toSet()));
        for (TenantTableCatalog.TableEntry entry : credentials) {
            assertFalse(entry.rlsEnabled(), entry.tableName()
                    + " 认证发生在确定租户之前，凭据表不得启用 RLS");
        }
    }

    @Test
    @DisplayName("未登记的表被明确拒绝，而不是静默放行")
    void unregisteredTableIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TenantTableCatalog.requireRegistered("rag_table_that_does_not_exist"));

        assertTrue(error.getMessage().contains("rag_table_that_does_not_exist"));
    }

    @Test
    @DisplayName("catalog 与 Flyway 迁移脚本中的存活表集合完全一致（防新增表漏登记）")
    void catalogMatchesMigrationCreateTableInventory() throws Exception {
        Path migrationDir = Paths.get("src", "main", "resources", "db", "migration");
        assertTrue(Files.isDirectory(migrationDir), "应能定位 Flyway 迁移目录");

        List<Path> files;
        try (var stream = Files.list(migrationDir)) {
            files = stream.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .sorted(Comparator.comparingInt(p -> Integer.parseInt(
                            p.getFileName().toString().replaceFirst("^V(\\d+)__.*$", "$1"))))
                    .toList();
        }
        // 动态基线：从 V1 到最大版本号必须连续、无缺号（不写死总数，避免新增迁移时机械漂移）。
        int maxVersion = files.stream()
                .mapToInt(p -> Integer.parseInt(
                        p.getFileName().toString().replaceFirst("^V(\\d+)__.*$", "$1")))
                .max().orElseThrow();
        assertEquals(maxVersion, files.size(),
                "V1..V" + maxVersion + " 必须连续且无重复版本");

        Pattern create = Pattern.compile(
                "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-zA-Z0-9_.\"]+)",
                Pattern.CASE_INSENSITIVE);
        Pattern drop = Pattern.compile(
                "DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?([a-zA-Z0-9_.\"]+)",
                Pattern.CASE_INSENSITIVE);
        Set<String> created = new LinkedHashSet<>();
        Set<String> dropped = new HashSet<>();
        for (Path file : files) {
            String sql = Files.readString(file);
            Matcher m = create.matcher(sql);
            while (m.find()) {
                created.add(normalizeTableName(m.group(1)));
            }
            m = drop.matcher(sql);
            while (m.find()) {
                dropped.add(normalizeTableName(m.group(1)));
            }
        }
        created.removeAll(dropped);
        // 存活表数量不写死：以迁移脚本实际 CREATE/DROP 结果为准，与 catalog 集合比对。
        assertFalse(created.isEmpty(), "迁移脚本中应至少存在一张存活业务表");

        Set<String> catalogNames = TenantTableCatalog.entries().stream()
                .map(TenantTableCatalog.TableEntry::tableName)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(created, catalogNames,
                "catalog 必须逐张覆盖迁移脚本中的存活业务表；新增表先登记 catalog 与裁决文档再写迁移");
    }

    private static String normalizeTableName(String raw) {
        String name = raw.replace("\"", "");
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    @Test
    @DisplayName("已登记的表可以按名字取回")
    void registeredTableCanBeLookedUp() {
        TenantTableCatalog.TableEntry entry =
                TenantTableCatalog.requireRegistered("rag_collection");

        assertEquals("rag_collection", entry.tableName());
    }
}

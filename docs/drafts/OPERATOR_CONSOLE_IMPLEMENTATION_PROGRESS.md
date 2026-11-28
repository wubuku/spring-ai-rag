# Spring AI RAG 多租户 P0/P1 修复与运营方管理控制台实施进度账本

> 更新时间：2026-11-27  
> 关联文档：[多租户成熟度与 P0 审计](MULTI_TENANCY_MATURITY_AND_P0_AUDIT.md) | [运营方控制台总设计与实施规划](OPERATOR_CONSOLE_DESIGN_AND_PLAN.md) | [UI 原型施工手册](OPERATOR_CONSOLE_UI_CONSTRUCTION_MANUAL.md)

## 1. 已完成（代码已落地，沙箱离线校验通过项已标注）

### Phase 0：多租户 P0-1..P0-4 缺陷修复（后端）
- **P0-1**：`TenantRlsVerifier.java` —— 全量遍历 `pg_policies` 拒绝多策略旁路，加固 `isPermissivePolicy` 拒绝 `OR true`，补充 `isValidTenantPolicyExpression` 同时校验 `USING` 与 `WITH CHECK` 绑定 `app.current_tenant_id`。
- **P0-2**：`ApiKeyManagementService.java` —— 新增 `issueKeyForTenant(tenantId, request, managed)` 与 `issueKeyForExistingPrincipal(tenantId, principalId, expiresAt, managed)`，显式写入 `RagApiPrincipal.tenantId` 并打通开户 bootstrap 孤儿主体首次签发。
- **P0-3**：`PrincipalTenantResolver.java` + `TenantContextFilter.java` —— 新增 `evaluateDenial()`；在 `STRICT` 模式下，已认证数据库主体归属 `SUSPENDED` / `DISABLED` / 非 `ACTIVE` 租户时在边缘短路返回显式 `403`（`TENANT_SUSPENDED` / `TENANT_DISABLED` / `TENANT_INACTIVE`）。
- **P0-4**：`TenantLifecycleService.java`（禁用/软删保护 `default` 租户返回 `DEFAULT_TENANT_PROTECTED`，新增 `updateDisplayName`）、`TenantLifecycleController.java`（放通经数据库认证的 `lifecycle-actors` 进入 `PlatformCapabilityRegistry` 白名单校验，统一 `DEFAULT_TENANT_PROTECTED` 409 错误码）、`TenantDataSourceBeanPostProcessor.java`（显式排除 `platformDataSource` 与 `flywayDataSource`）。
- **单测**：新增 `spring-ai-rag-core/src/test/java/com/springairag/core/tenant/TenantP0FixesTest.java`。

### Phase 2：V86 迁移、持久化审计与运营方控制台后端 API
- **迁移**：新增 `spring-ai-rag-core/src/main/resources/db/migration/V86__operator_console_audit_and_support.sql`（`rag_platform_audit_event`、`rag_operator_support_session`）。
- **目录与路由守卫**：更新 `TenantTableCatalog.java`（登记 V86 两张 `PLATFORM_GLOBAL` 表，总计 78 表）与 `TenantEndpointDeclarationCatalog.java`（将 `/api/v1/rag/operator/**` 声明为 `PLATFORM_ONLY + PLATFORM_CAPABILITY`）。
- **仓储、服务与控制器**：扩展 `TenantQuotaPolicyRepository.java`（`listAll` / `upsert`）；新增 `JdbcTenantLifecycleAudit.java`、`OperatorConsoleService.java`、`OperatorConsoleController.java`；在 `TenantConfiguration.java` 完成条件装配。
- **单测**：新增 `spring-ai-rag-core/src/test/java/com/springairag/core/tenant/OperatorConsoleCatalogAndRoutesTest.java`。

### Phase 1 + Phase 3：运营方管理控制台前端工程（`spring-ai-rag-operator-console/`）
- **视觉与契约**：落地 `xingmu-ui-contract-v1`（亮/暗双主题 `src/index.css`）、冻结契约 `src/domain/operatorContract.ts`、确定性夹具 `src/domain/fixtures.ts`（`MOCK_NOW` + 6 边界租户）、内存仓库 `src/domain/mockRepository.ts`、真实后端客户端 `src/domain/httpOperatorClient.ts`、双客户端开关 `src/domain/clientFactory.ts`（`VITE_OPERATOR_CLIENT=mock|real`）。
- **界面与交互**：落地 6 视图（`TenantsPage`、`CredentialsPage`、`QuotasPage`、`KnowledgeHealthPage`、`CompliancePage`、`AuditSupportPage`）、`TenantDrawer`、`ConfirmModal`、`OneTimeKeyModal`、`CommandPalette`（`Cmd+K`）、`DevPanel`（Mock 401/403/409 负向开关）、`j`/`k`/`Enter`/`Esc`/`[` 快捷键与 390px 堆叠卡片响应式。
- **沙箱离线验证**：复借本地 `AI-Agent-Operator-Console/node_modules` 与 Node 24 执行 `tsc --noEmit` 与 `vite build`，均 **0 错误通过**。

## 2. 待全权限环境接续完成项

1. **后端编译与单测/集成测试**（沙箱无 JDK/Maven/Docker）：运行 `mvn test` 与 STRICT PostgreSQL 集成测试（补充并运行第 2 条 `USING (true)` 拒启、纯 HTTP 双租户建户发 Key 隔离读写、V86 Flyway 迁移与 `/api/v1/rag/operator/**` 集成测试）。
2. **前端独立依赖安装与 Playwright 冒烟**：在 `spring-ai-rag-operator-console/` 执行 `npm install`、`npm run build`，补写并运行 Playwright Mock/Real E2E 冒烟脚本（覆盖 6 视图、开户一次性 Key、`default` 保护、DevPanel 403、390px 无横向溢出）。
3. **P0-5 大规模真实 PG 验证**：30 万行非均匀语料（80%/15%/5%）HNSW `iterative_scan` `Recall@10` 压测 + 百万行 `rag_embeddings` 在线 `CONCURRENTLY` 锁窗口演练，结果回填至 [升级演练记录](MULTI_TENANCY_UPGRADE_REHEARSAL.md)。
4. **文档门禁与 Git 提交**：运行 `./scripts/verify-project-docs.sh` 并按逻辑拆分为干净的本地提交（Phase 0 / Phase 2 / Phase 1+3）。

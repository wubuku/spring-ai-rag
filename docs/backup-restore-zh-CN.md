# 数据备份与隔离恢复

> [English](backup-restore.md) | [中文](backup-restore-zh-CN.md)

## 边界

`scripts/rag-backup.py` 是主动执行的 PostgreSQL 逻辑备份/恢复工具，不是云灾备服务、增量备份、
PITR 或密钥托管。正常服务启动不自动导出数据。上传原件在 `fs_files.content_bin`；文档、向量、
证据快照/历史、权限、任务与删除状态随同一数据库快照保存，无需另外备份临时 PDF 转换目录。
外部 Resource URI 指向的文件、环境配置和模型凭据不在范围内，恢复前由部署环境另行提供。

备份**包含敏感业务数据**，可能包括存储在业务表中的敏感内容，不是脱敏制品。工具不打包 `.env`、
日志或外部模型配置文件；目录 0700、文件 0600。必须使用可信存储、加密和访问控制；校验和只能证明
完整性，不能证明来源可信。不要把备份提交 Git 或恢复不可信来源的 SQL/archive。

## 前置条件

- `python3`（3.10+）、`psql`、`pg_dump`、`pg_restore`，推荐使用与服务器同主版本的 PostgreSQL 工具。
- 显式 `PGHOST`、`PGPORT`、`PGDATABASE`；认证用 `PGUSER`、安全注入的 `PGPASSWORD` 或 `.pgpass`。
  不支持连接 URI 或 `PGSERVICE`，不把密码放入命令参数、清单或日志。
- 停止源服务写入和后台 worker，再备份；`--quiesced` 是操作者确认，不会擅自停止任何服务。
  DB 本身的 dump 是一致性快照；停写让任务/删除状态和业务恢复点可解释。
- 恢复到**全新、空的、没有连接业务服务的隔离库**；使用具备创建扩展/表权限的受控角色。
  不导出/还原集群角色或密码，`--no-owner --no-privileges` 由目标部署角色承担权限管理。
  目标 PostgreSQL 必须已安装项目所需的 pgvector 等服务端扩展；dump 不携带扩展二进制。

## 备份与校验

先安全设置上述 libpq 环境变量（不得在共享终端打印密码）：

```bash
python3 scripts/rag-backup.py backup /secure/backups/rag-20261018 \
  --ack-sensitive-data --quiesced
python3 scripts/rag-backup.py verify /secure/backups/rag-20261018
```

父目录需预先存在，备份目录必须尚不存在。输出 `database.dump` 和 version=1 `manifest.json`
（Flyway 版本、SHA-256、大小、UTC 时间、不可逆连接身份摘要）。失败不会发布完整清单；残缺目录
必须人工检查，工具不覆盖或删除。不要从命令输出“PASS”推导出业务删除审查已完成。

## 隔离恢复与放流量

将环境变量改为目标空库，再执行：

```bash
python3 scripts/rag-backup.py restore /secure/backups/rag-20261018 --ack-isolated-target
```

恢复前校验 archive/清单/大小/hash，拒绝源库和非空库，不运行 clean/drop/truncate。
`pg_restore --single-transaction --exit-on-error` 保证数据库 SQL 恢复原子性；还原后核对 Flyway 版本。
目标必须排他隔离，操作者不能在检查与恢复之间让别的服务写入。启动同版本服务完成验证，再按正常
升级流程运行新版本 Flyway，不能自行编辑已执行迁移或把旧数据冒充新 schema。

**旧备份可能复活备份之后已经删除的数据。** 在接生产流量前，必须按最新可用删除/保留清单重放删除、
核对 tombstone 和外部权限，并完成向量/原件/历史残留检查。无法获得备份之后的删除记录时，不能声称
满足 RPO=0 或隐私删除连续性；保持隔离，明确恢复点。不要手工把未完成 reservation 改成已结算，
让既有 lease/reconciliation 接管并检查结果。

放流量门槛：健康与 Flyway、活动 embedding profile、原件哈希、检索/证据快照、已删除样本、
Scoped API Key 读写和 Collection 隔离、历史、Native/OpenAI JSON/SSE，以及实际客户端契约。
按实际备份频率定义 RPO，按演练测量 RTO，不把单次测试耗时作为生产 SLA。

## 可重复隔离演练

```bash
mvn clean compile test-compile
python3 scripts/test-support/rag-backup-self-test.py
python3 scripts/verify-dependency-recovery-e2e.py
```

演练只创建自己的 PostgreSQL 容器/临时库，使用受控上游，真实执行 dump/restore、逐表内容对账、
文件/向量/权限/删除与外部 HTTP 检查；额外覆盖故障、混合负载、强杀/正常重启和恢复负例。
证据在 `.verification/dependency-recovery/`，不会提交；最终自动停止自有进程并删除自有容器。
它证明本构建的有界恢复路径，不等于真实模型质量、异地备份可达性或云故障切换。

其他门槛见[测试指南](testing-guide-zh-CN.md)；旧 `verify-recovery-readiness.sh` 仍只证明迁移/治理就绪，
不能替代本演练。

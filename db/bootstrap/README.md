# InfraHub 数据库手动初始化脚本

这些脚本用于手动创建或验收本地/测试数据库。运行时 Flyway 迁移源以 `db/flyway/sql` 为准（构建期映射到 classpath 的 `db/migration`，详见 `db/flyway/README.md`）。

## 建议顺序

1. `00-create-database.sql`
2. `00-post-create-database.sql`
3. `01-schema.sql`
4. `02-init-data.sql`
5. `02-repair-identity-sequences.sql`
6. `03-db-acceptance.sql`

## 说明

- `01-schema.sql` 是手动初始化基线 schema。
- `02-init-data.sql` 是基础种子数据。
- `03-db-acceptance.sql` 只做验收检查和事务回滚式写入探测。
- 后续共享环境变更应通过 Flyway 迁移，不应直接修改已使用的基线脚本。

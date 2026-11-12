# 数据库初始化与迁移

本地全新环境使用[根目录 Compose](../../docker-compose.yml)。
PostgreSQL 会执行 `db/bootstrap` 的初始化脚本，Compose 启动的后端关闭 Flyway。

应用打包时将 `sql/` 映射到 `classpath:db/migration`。已有环境如启用 Flyway，
先核对该库的迁移版本和校验和，再在独立环境预演。已应用的迁移脚本保持原样。
旧版本缺失迁移的身份由 `legacy-missing-migration-allowlist.txt` 约束。

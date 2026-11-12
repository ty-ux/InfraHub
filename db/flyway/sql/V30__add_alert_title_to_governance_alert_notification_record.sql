-- 前向补齐通知表的实体契约缺口。
--
-- 缺口：V28 建表时漏了 alert_title，而实体
-- com.infrahub.release.entity.GovernanceAlertNotificationRecord 的 alertTitle 字段
-- 及其服务会读写该列。在 baseline=2 的隔离库上按迁移链建表后查询该列，复现
-- SQLSTATE 42703 (column does not exist)。真实库与 db/bootstrap/04-01-add-missing-tables.sql
-- 都已有该列，所以线上不暴露此缺口——它是原重建脚本携带的既有缺陷，不是改编号引入的。
--
-- 为什么不改 V28：V28/V29 已在真实库应用并记入 flyway_schema_history。改写已执行脚本会让
-- 校验和与历史不一致，只能靠 repair 掩盖。按迁移纪律改为新增版本前向补齐。
--
-- 幂等性：真实库已有该列，故此处必须 IF NOT EXISTS。列定义对齐真实库
-- （可空 VARCHAR(512)），不加约束、不改已有列、不动表上其他对象，也不重建表。
-- 注释文本取真实库的 '告警标题'；db/bootstrap/04-01-add-missing-tables.sql 中的注释文本
-- 不同（含实体字段说明），属已知差异，见 docs/database-migration-paths.md，不在此处跟随。
--
-- 表不存在时不加 IF EXISTS：V28 在本链中先于本脚本，若表缺失应当显式失败而不是静默跳过。

ALTER TABLE release_governance_alert_notification_record
    ADD COLUMN IF NOT EXISTS alert_title VARCHAR(512);

COMMENT ON COLUMN release_governance_alert_notification_record.alert_title IS '告警标题';
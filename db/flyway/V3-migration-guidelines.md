# V3+ Migration Guidelines

本文档用于约束 InfraHub 在 `baselineVersion=2` 之后的数据库变更方式。

## 1. 起始版本

当前数据库基线为：

- `V1__baseline_schema.sql`
- `V2__seed_reference_data.sql`

因此，后续第一批正式迁移从 `V3__...` 开始。

## 2. 命名规范

文件名格式：

```text
V{version}__{short_description}.sql
```

示例：

- `V3__add_release_order_operator_columns.sql`
- `V4__create_pipeline_lock_table.sql`
- `V5__seed_monitor_rule_templates.sql`

要求：

- 使用双下划线 `__`
- 描述使用小写英文和下划线
- 名称要表达“这次改了什么”，不要写 `update_table.sql`

## 3. 拆分原则

### 3.1 一个迁移只做一类事情

推荐：

- 一个表新增字段
- 一个新表创建
- 一组强相关索引补充
- 一组初始化字典补充

不推荐：

- 在同一个迁移里同时大改 6 张表和大量历史数据

### 3.2 结构变更与数据修复

优先策略：

- 小范围且强耦合：可以放同一个迁移
- 大范围数据回填：单独拆成一个迁移

推荐顺序：

1. 先加字段，允许为空
2. 再回填历史数据
3. 再加非空约束或唯一约束

## 4. SQL 编写规范

### 4.1 幂等优先

能写成幂等就写成幂等。

推荐：

```sql
ALTER TABLE release_order
    ADD COLUMN IF NOT EXISTS operator_name VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_release_order_operator_name
    ON release_order (operator_name);
```

### 4.2 避免直接删除

生产环境中不建议在早期阶段使用以下操作：

- `DROP COLUMN`
- `DROP TABLE`
- 直接修改已有字段类型导致锁表

更稳妥的方式：

- 先新增替代字段
- 回填数据
- 应用切换读取逻辑
- 后续版本再清理旧字段

### 4.3 大表索引要谨慎

如果未来数据量变大，新增索引要评估锁影响。

当前阶段可接受：

- 直接 `CREATE INDEX`

后续进入生产规模后建议评估：

- `CREATE INDEX CONCURRENTLY`

说明：

- `CREATE INDEX CONCURRENTLY` 不能放在事务中
- 如果使用它，需要单独规划 Flyway 执行策略

## 5. 审查清单

每个新迁移提交前至少检查：

- 是否会影响现有数据
- 是否需要回填历史数据
- 是否需要新增索引
- 是否会引入长时间锁表
- 是否需要更新初始化数据或字典
- 是否需要同步更新设计文档

## 6. 第一批 V3 示例建议

如果我们接下来开始开发，第一批迁移通常会集中在这些方向：

### 6.1 V3 字段增强

目标：

- 给发布、流水线、实例等主链路补充更细的运维字段

示例：

- `V3__add_release_order_operator_columns.sql`

### 6.2 V4 新增运行控制表

目标：

- 增加执行锁、审批记录、任务分发表等运行控制表

示例：

- `V4__create_pipeline_lock_table.sql`

### 6.3 V5 数据字典补充

目标：

- 补充新的状态枚举或监控模板

示例：

- `V5__seed_sys_enum_monitor_types.sql`

## 7. 团队约定

- 已执行过的迁移文件禁止修改
- 修复问题请新增 `V{next}__...` 迁移，而不是改旧版本
- 文档、SQL、代码中的枚举和字段命名要同步
- 所有线上结构变更必须先经过测试库验证

## 8. 推荐落地方式

开发流程建议：

1. 改设计文档
2. 新增 Flyway 迁移
3. 本地执行 `migrate`
4. 跑数据库验收脚本
5. 再提交代码和接口实现

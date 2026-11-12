package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("runtime_deploy_record")
public class RuntimeDeployRecord {

    private Long id;
    private Long releaseOrderId;
    private Long rollbackOrderId;
    private String operationType;
    private Long serviceId;
    private Long envId;
    private Long hostId;
    private Long versionId;
    private Integer deployBatch;
    private String deployStatus;
    private String deployLog;
    private OffsetDateTime startedAt;
    private OffsetDateTime endedAt;
    /**
     * 产生这条记录的发布执行认领（{@code release_execution_run_claim.id}）。
     *
     * <p>可空：V32 迁移之前的历史记录，以及凭据是订单/批次条件更新而不是持久认领的执行路径
     * （直接发布、灰度批次）都没有认领行。有值时，这条记录与它的发布单、流水线运行、
     * 步骤运行属于同一次执行，页面据此显示归属。
     *
     * <p><b>故意不参与 ORM 映射</b>（{@code exist = false}）：V32 未应用时数据库中不存在
     * {@code execution_claim_id} 列，而 MyBatis-Plus 会按实体字段生成 {@code SELECT} 列清单，
     * 映射这个字段会让**所有**部署记录读取（含只读列表、恢复路径）在旧库上直接报错。
     * 因此这里只把它当作调用链上的内存载体：创建记录时由部署执行服务填入，
     * 落库与读取都走 {@link com.infrahub.release.mapper.RuntimeDeployRecordMapper} 里的显式 SQL，
     * 并且只在确实拿到认领行（即 V32 已应用）时才执行。
     */
    @TableField(exist = false)
    private Long executionClaimId;
    /**
     * 这条记录实际部署的实例（{@code runtime_service_instance.id}）。
     *
     * <p>只在**内存中**承载一次执行与实例身份的绑定：创建记录时由部署执行服务填入，
     * 落库与读取都走 {@link com.infrahub.release.mapper.RuntimeDeployRecordInstanceMapper} 的显式 SQL。
     *
     * <p><b>故意不参与 ORM 映射</b>（{@code exist = false}）：实例归属存放在新表
     * {@code runtime_deploy_record_instance}（V34），而不是部署记录表的列。若把它映射成列，
     * MyBatis-Plus 生成的 {@code SELECT} 列清单会引用不存在的列，使**所有**部署记录读取
     * （含只读列表）在未应用 V34 的库上直接报错。
     *
     * <p>语义上它比 {@code hostId} 更强：同一台主机上可以有多个实例，只有实例 id 能区分
     * “这次到底部署了谁”。为空表示这条记录不带实例身份（例如回滚路径），读取方按
     * **无法证明**处理，而不是按“不需要归属”处理。
     */
    @TableField(exist = false)
    private Long instanceId;
    @TableField(exist = false)
    private String instanceName;
    @TableField(exist = false)
    private String instanceType;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}

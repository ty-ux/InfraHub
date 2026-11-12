package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 一条部署记录**实际部署的那个实例**（{@code runtime_deploy_record_instance}）。
 *
 * <p><b>为什么必须是独立事实</b>：部署记录只带服务/环境/主机，不带实例身份；同一个主机上可以跑
 * 同一服务的多个实例，实例还会在执行期间被新增、移除或改绑。因此“本次执行部署了哪些实例”
 * 不能在执行结束时按服务+环境重新枚举得到——那样会把执行期间新增、实际并未部署的实例也算进去，
 * 并把新版本写到它们身上。
 *
 * <p>关联在**记录创建时**、插入的同一个短事务里写入，之后不再回填，历史记录不做事后推断。
 *
 * <p>{@code instanceId} / {@code hostId} 是执行时观测到的身份快照：实例或主机之后被移除时，
 * 这条关联仍然保留，读取方按“当前实例行不存在”判定为**无法证明**（失败关闭），
 * 而不是判定为“没有部署”。
 */
@Data
@TableName("runtime_deploy_record_instance")
public class RuntimeDeployRecordInstance {

    private Long id;
    /** 产生的部署记录；一条记录对应一个实例。 */
    private Long deployRecordId;
    /** 执行时观测到的实例 ID。故意没有外键：实例可以随后被移除。 */
    private Long instanceId;
    private Long serviceId;
    private Long envId;
    /** 执行时观测到的主机 ID；之后改绑由与当前行比对发现，不重写本列。 */
    private Long hostId;
    /** 执行时观测到的实例名，便于实例行被移除后仍可核对。 */
    private String instanceName;
    private String instanceType;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}

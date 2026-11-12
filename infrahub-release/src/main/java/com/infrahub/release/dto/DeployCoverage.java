package com.infrahub.release.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次执行的**实例覆盖范围**：这次执行的成功证据落在当前实例集合里的哪些实例上。
 *
 * <p>本视图只回答“这次执行覆盖了谁”，**不**回答“这些实例现在还在不在该版本上”。
 * 后一个问题由 {@link VersionRegistrationConsistency} 单独表达。两者必须分开读：
 * 一次历史发布可以覆盖当时的全部实例，而其中一些实例此后可能被再次发布、回滚或改绑。
 *
 * <p>{@code verdict=FULL} 只表示“本次执行的成功证据覆盖了当前实例集合的全部实例”，
 * 不表示这些实例现在仍登记在该版本，也不表示远端实际运行版本。页面不得把 FULL 读成
 * “当前所有实例都已部署到该版本”或“无需处理”。
 *
 * <p><b>证据来源</b>：发布类执行使用执行认领、该认领产出的部署记录，以及记录创建时写下的实例归属；
 * 灰度执行使用已保存的灰度批次清单、批次成功状态和同一批次的部署记录/实例归属；
 * 回滚执行的部署记录不带实例身份，因此回滚来源的覆盖范围结构上不可证明。
 *
 * <p><b>读取快照</b>：本视图是只读快照，不持锁。{@code uncoveredInstanceIds} 是“**现在**的实例集合”
 * 减去“**当时**这次执行证明成功的实例”。执行之后新增的实例会出现在这里；它没有被这次执行覆盖，
 * **不是部署失败**。执行期间被移除或改绑的实例会让证据整体不可证明，平台不会挑出
 * “看起来没问题”的部分照做。
 */
@Data
public class DeployCoverage {

    /** 本次执行的成功证据覆盖当前实例集合的全部实例；仍不代表当前登记版本一致。 */
    public static final String VERDICT_FULL = "FULL";
    /** 本次执行只覆盖当前实例集合的一部分；未覆盖实例没有参与本次执行，不是失败。 */
    public static final String VERDICT_PARTIAL = "PARTIAL";
    /** 无法证明本次执行落在哪些实例上：不得给出任何覆盖结论。 */
    public static final String VERDICT_UNPROVABLE = "UNPROVABLE";
    /** 没有可归属的执行（没有执行记录/批次）：没有覆盖范围可言。 */
    public static final String VERDICT_NOT_STARTED = "NOT_STARTED";

    public static final String SOURCE_RELEASE = "RELEASE";
    public static final String SOURCE_CANARY = "CANARY";
    public static final String SOURCE_ROLLBACK = "ROLLBACK";
    public static final String SOURCE_NONE = "NONE";

    public static final String REJECTION_NO_SOURCE = "NO_SOURCE";
    public static final String REJECTION_SOURCE_MISSING = "SOURCE_MISSING";
    public static final String REJECTION_SOURCE_MISMATCH = "SOURCE_MISMATCH";
    public static final String REJECTION_NO_CLAIM = "NO_CLAIM";
    public static final String REJECTION_CLAIM_UNAVAILABLE = "CLAIM_UNAVAILABLE";
    public static final String REJECTION_CLAIM_NOT_CLOSED = "CLAIM_NOT_CLOSED";
    public static final String REJECTION_CLAIM_ABANDONED = "CLAIM_ABANDONED";
    public static final String REJECTION_NO_RECORDS = "NO_RECORDS";
    public static final String REJECTION_RECORDS_NOT_SUCCESS = "RECORDS_NOT_SUCCESS";
    public static final String REJECTION_RECORD_SCOPE_MISMATCH = "RECORD_SCOPE_MISMATCH";
    public static final String REJECTION_VERSION_MISMATCH = "VERSION_MISMATCH";
    public static final String REJECTION_ATTRIBUTION_UNAVAILABLE = "ATTRIBUTION_UNAVAILABLE";
    public static final String REJECTION_ATTRIBUTION_MISSING = "ATTRIBUTION_MISSING";
    public static final String REJECTION_ATTRIBUTION_AMBIGUOUS = "ATTRIBUTION_AMBIGUOUS";
    public static final String REJECTION_ATTRIBUTION_MISMATCH = "ATTRIBUTION_MISMATCH";
    public static final String REJECTION_DUPLICATE_INSTANCE = "DUPLICATE_INSTANCE";
    public static final String REJECTION_EXPECTED_INSTANCE_MISSING = "EXPECTED_INSTANCE_MISSING";
    public static final String REJECTION_UNEXPECTED_INSTANCE = "UNEXPECTED_INSTANCE";
    public static final String REJECTION_INSTANCE_REMOVED = "INSTANCE_REMOVED";
    public static final String REJECTION_INSTANCE_REBOUND = "INSTANCE_REBOUND";
    public static final String REJECTION_ROLLBACK_UNPROVABLE = "ROLLBACK_UNPROVABLE";
    public static final String REJECTION_CANARY_PLAN_MISSING = "CANARY_PLAN_MISSING";
    public static final String REJECTION_CANARY_NO_SUCCESS = "CANARY_NO_SUCCESS";
    public static final String REJECTION_CANARY_BATCH_INVALID = "CANARY_BATCH_INVALID";
    public static final String REJECTION_NO_PROVEN_INSTANCE = "NO_PROVEN_INSTANCE";

    private String verdict;
    private boolean evidenceProvable;
    /** 不可证明时的稳定分类（供测试与页面分支使用），可证明时为 null。 */
    private String rejectionCode;
    /** 不可证明时面向操作者的原因；不含内部表名、迁移号、命令或远端输出。 */
    private String rejectionReason;

    /** 证据来源：RELEASE / CANARY / ROLLBACK。 */
    private String sourceType;
    private Long sourceOrderId;
    private String sourceOrderNo;
    private Long sourceClaimId;

    /** 本次执行的目标版本；用于核对部署记录是否属于这个版本。 */
    private Long targetVersionId;
    private String targetVersionNo;

    /** 本次执行证明成功部署的当前实例（升序、去重）。 */
    private List<Long> deployedInstanceIds = new ArrayList<>();
    /**
     * 当前存在、但没有被本次执行覆盖的实例（升序）。
     * 可能是执行期间/之后新增的实例，也可能本来就不在这次执行范围内；**都不是部署失败**。
     */
    private List<Long> uncoveredInstanceIds = new ArrayList<>();
    private int deployedCount;
    private int uncoveredCount;
    /**
     * 灰度执行中位于**未成功批次**里的当前实例（升序）。
     *
     * <p>它们参与过本次灰度的计划，但所在批次失败/暂停/尚未执行，平台没有它们的成功部署证据。
     * 与 {@code uncoveredInstanceIds} 不同：后者是根本没有进入本次执行范围的实例（例如执行后新增），
     * 页面必须分开表述，不能把未成功批次的实例说成“未参与”。
     */
    private List<Long> unfinishedInstanceIds = new ArrayList<>();
    private int unfinishedCount;
    /** 该服务/环境当前的实例总数。 */
    private int currentInstanceCount;

    /** 本次证据指向、但当前已被移除的实例；非空时整体不可证明。 */
    private List<Long> removedInstanceIds = new ArrayList<>();
    /** 本次证据指向、但当前服务/环境/主机已变化的实例；非空时整体不可证明。 */
    private List<Long> reboundInstanceIds = new ArrayList<>();

    /** 可展示的一句话表述：FULL 也不得表述为“当前登记版本一致”。 */
    private String statement;
    /** 操作人下一步能做什么；平台不支持按单个实例发布时会如实说明范围。 */
    private String nextStep;
    /** 展示用免责声明：平台不探测远端、不自动补发。 */
    private String disclaimer;

    public static final String DISCLAIMER =
            "覆盖范围只表示本平台持久化的执行记录与实例归属；平台不连接远端、不探测实际运行版本、"
                    + "不重试、不自动补发。";
}

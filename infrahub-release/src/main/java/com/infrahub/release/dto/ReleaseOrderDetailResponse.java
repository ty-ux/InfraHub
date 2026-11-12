package com.infrahub.release.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
public class ReleaseOrderDetailResponse {

    private Long id;
    private String orderNo;
    private String status;
    private String reason;
    private OffsetDateTime createdAt;

    private String appName;
    private String serviceName;
    private String envName;

    private String versionNo;

    private String latestDeployStatus;
    private String latestFailureHint;
    private Boolean hasPrecheckRecords;
    private Boolean hasDeployRecords;
    private Long pipelineRunId;

    private String currentVersionNo;
    private String currentVersionSourceReleaseOrderNo;
    private String currentVersionSourceRollbackOrderNo;

    /**
     * 本单这次执行的实例覆盖范围：证明部署成功的实例，以及当前实例集合里未被本次覆盖的实例。
     *
     * <p>订单结果为 SUCCESS 只说明“这次执行产出的部署记录全部成功”，**不**说明该服务/环境当前
     * 所有实例都在该版本上。页面必须按 {@code verdict} 表述；即使 {@code FULL}，也只表示
     * “本次执行覆盖了当前实例集合的全部实例”，不表示这些实例现在仍登记在该版本。
     * 当前登记版本是否一致由 {@link #registrationConsistency} 单独表达。
     */
    private DeployCoverage deploymentCoverage;

    /**
     * 本单目标版本与当前实例登记版本的一致性。
     *
     * <p>与 {@link #deploymentCoverage} 是两件事：历史发布成功不代表实例现在仍登记在该版本。
     * 该视图逐个实例比较当前登记版本，并明确说明这是平台登记状态，不是远端实际运行版本。
     */
    private VersionRegistrationConsistency registrationConsistency;

    private List<TimelineNode> timeline;
    private List<ExecutionHistoryItem> executionHistory;

    private String executorType;
    private Long executorConfigId;
    private String executorConfigName;
}

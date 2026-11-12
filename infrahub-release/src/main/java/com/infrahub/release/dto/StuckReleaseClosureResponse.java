package com.infrahub.release.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 卡住发布单人工收口的结果。
 *
 * <p>它回答三件事：操作人给出的结论是什么、平台据此改了什么、还有什么仍然挡着。
 * {@code blockingRemains=true} 表示未解除的未知事实仍在，服务/环境**并没有**因此放行——
 * 收口订单不会解除任何未知事实。
 */
@Data
public class StuckReleaseClosureResponse {

    public static final String OUTCOME_SUCCEEDED = "SUCCEEDED";
    public static final String OUTCOME_FAILED = "FAILED";

    private String target = "RELEASE";
    private Long releaseOrderId;
    private String outcome;
    private String orderStatus;

    /** 对账到的部署认领（可能为空）。 */
    private Long claimId;
    private String claimStatus;
    private String claimReleaseOutcome;
    private Long pipelineRunId;
    private String pipelineRunStatus;

    /** SUCCEEDED 时实际更新版本的实例（升序）；FAILED 时为空。 */
    private List<Long> updatedInstanceIds = new ArrayList<>();
    /** 该服务/环境当前存在、但本次部署未覆盖的实例（不会被更新版本）。 */
    private List<Long> currentInstanceIdsNotDeployed = new ArrayList<>();

    private int deployRecordCount;
    private int unresolvedFactCountForOrder;
    private int unresolvedFactCountForServiceEnv;
    /** 未解除的未知事实是否仍然挡住该服务/环境。 */
    private boolean blockingRemains;

    /**
     * 收口**之后**仍然可用的路径（在同一把锁内重新判定）。订单已不在执行中，
     * 因此收口类路径都会消失，通常只剩“逐条解除未解除的未知事实”等。
     */
    private List<ReleaseClosureRouteView> remainingRoutes = new ArrayList<>();
    private boolean anyRemainingRouteAvailable;
    /**
     * 收口之后的下一步说明。没有可用路径时如实写明阻断仍在、平台内没有入口，
     * 而不是把操作者引到一条走不通的路。
     */
    private String nextStepSummary;

    /** 操作人结论的可读表述与依据。 */
    private String conclusion;
    /** 展示用免责声明：结论来自操作人，平台没有探测远端。 */
    private String disclaimer;
}

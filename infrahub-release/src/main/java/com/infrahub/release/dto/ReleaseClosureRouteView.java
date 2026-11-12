package com.infrahub.release.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 卡住发布单**可选路径**的准入判定（只读）。
 *
 * <p>存在的理由：收口预览原先只回答“这次收口会不会被接受”。当它被拒绝时，拒绝文案会指向
 * 另一条入口（“请走结果未知恢复”“请先登记结果未知并人工核对”），但那条入口自身也有前提：
 * 没有部署记录、记录都已是确定结果、没有认领行、V31/V32/V34 未应用时，它会同样拒绝。
 * 于是操作者被引导到一条**走不通**的路，反复尝试后仍然不知道该怎么办。
 *
 * <p>本视图把每条路径单独判一遍，给出三件事实：
 * <ol>
 *   <li>{@code available} —— 现在按这条路径提交，会不会被接受；</li>
 *   <li>{@code blockingReasons} —— 不接受时的逐条原因，可直接展示给操作者；</li>
 *   <li>{@code actionPath} —— 平台入口。**不可用时为 null**：不给不可执行的引导。</li>
 * </ol>
 *
 * <p><b>它不是许可证</b>：本视图不加锁，是读取瞬间的快照。真正提交时，各入口会在服务行锁与
 * 订单行锁内重新判定，结论可能不同。
 *
 * <p><b>纪律</b>：{@code requiresRemoteManualCheck=true} 的路径（放弃认领、声明失败、宣布成功）
 * 都建立在操作人**平台之外**的核对之上。平台不连接远端、不重试、不回滚，也不从超时或进程无响应
 * 推断远端已停止；“放弃认领”只说明平台不再认为这次执行在飞。
 */
@Data
public class ReleaseClosureRouteView {

    /** 人工收口为成功：{@code POST /stuck-release/{id}/closure} + {@code outcome=SUCCEEDED}。 */
    public static final String ROUTE_ADOPT_SUCCESS = "ADOPT_SUCCESS";
    /** 人工收口为失败：{@code POST /stuck-release/{id}/closure} + {@code outcome=FAILED}。 */
    public static final String ROUTE_CLOSE_AS_FAILED = "CLOSE_AS_FAILED";
    /** 恢复为结果未知：{@code POST /unknown-executions/recover}。 */
    public static final String ROUTE_RESOLVE_AS_UNKNOWN = "RESOLVE_AS_UNKNOWN";
    /** 逐条人工解除未解除的未知事实：{@code POST /unknown-executions/{id}/resolve} 或 {@code /legacy/resolve}。 */
    public static final String ROUTE_RESOLVE_UNKNOWN_FACTS = "RESOLVE_UNKNOWN_FACTS";
    /** 显式放弃一条 HELD 认领：{@code POST /release-execution-runs/claims/{id}/abandon}。 */
    public static final String ROUTE_ABANDON_CLAIM = "ABANDON_CLAIM";

    private String route;
    /** 面向操作者的路径名称。 */
    private String label;
    /** 现在按这条路径提交是否会被接受（读取瞬间的快照）。 */
    private boolean available;
    /** 不接受的原因，逐条可直接展示；为空表示没有已知阻碍。 */
    private List<String> blockingReasons = new ArrayList<>();
    /** 平台入口的 HTTP 路径；不可用时为 null，避免把操作者引到走不通的入口。 */
    private String actionPath;
    /**
     * 该路径的正确性是否依赖操作者在平台之外核对远端事实。
     * {@code true} 表示平台只按操作人的结论落库，本身不探测远端。
     */
    private boolean requiresRemoteManualCheck;
    /**
     * 现在不可用，但**条件会自行变化**（例如部署记录还在 30 分钟执行时间窗内），
     * 等一段时间后重试即可。为 false 时表示需要先改变本地事实（登记、解除、应用迁移等），
     * 单纯等待不会让路径变得可用。
     */
    private boolean retryableLater;
    /** 补充说明（例如“放弃认领不代表远端已停止”“解除后阻断才会消失”）。 */
    private String note;
}

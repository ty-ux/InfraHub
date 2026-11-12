package com.infrahub.release.service;

import com.infrahub.release.dto.ReleaseClosureRouteView;
import com.infrahub.release.entity.ReleaseExecutionRunClaim;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.entity.RuntimeDeployRecord;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 卡住发布单**可选路径**的准入判定。
 *
 * <p>本类只做一件事：把“人工收口”与“恢复为结果未知”在同一个输入上各判一遍，
 * 让两个入口的准入条件、拒绝原因与操作者看到的文案来自同一处。它不加锁、不写库、
 * **不改变任何准入边界** —— 判定结果与各入口自身的门槛逐条对应，边界由那些入口保有自己的最终裁定权。
 *
 * <p>存在的理由（原先的缺口）：收口被拒绝时，拒绝文案会指向“恢复为结果未知”，
 * 但那条入口在下列情形同样会拒绝，于是操作者被引到一条走不通的路：
 * <ul>
 *   <li>发布单**没有部署记录** —— 恢复以“不能证明远端操作未开始”拒绝；</li>
 *   <li>部署记录**都已是确定结果**（无 RUNNING / UNKNOWN）—— 恢复以“这不是结果未知”拒绝；</li>
 *   <li>**没有认领行**（历史直接发布、V32 应用之前的数据）—— 收口以“没有可核对的部署认领”拒绝；</li>
 *   <li>**V31 未应用** —— 恢复与解除都按失败关闭拒绝；</li>
 *   <li>**V32 未应用** —— 不存在任何认领行，也没有“放弃认领”这个动作。</li>
 * </ul>
 *
 * <p>因此本类区分三种“不可用”：条件会自行变化的（等 30 分钟执行时间窗，
 * {@code retryableLater=true}）、需要先改变本地事实的、以及平台内**本来就没有入口**的。
 * 第三种必须如实说明，而不是换成另一句引导。
 */
@Service
public class ReleaseClosureRouteAdvisor {

    /** 发布单不在执行中（收口与恢复都要求 RUNNING）。 */
    public static final String GATE_ORDER_NOT_RUNNING = "ORDER_NOT_RUNNING";
    /** 仍有未收敛的灰度计划。 */
    public static final String GATE_ACTIVE_CANARY_PLAN = "ACTIVE_CANARY_PLAN";
    /** 同一服务/环境仍有 HELD 认领。 */
    public static final String GATE_HELD_CLAIM = "HELD_CLAIM";
    /** 没有可核对的部署认领（含 V32 未应用）。 */
    public static final String GATE_CLAIM_MISSING = "CLAIM_MISSING";
    /** 认领存在但没有正常 RELEASED + COMPLETED。 */
    public static final String GATE_CLAIM_NOT_CLOSED = "CLAIM_NOT_CLOSED";
    /** 仍有停在 RUNNING 的部署记录。 */
    public static final String GATE_RUNNING_RECORDS = "RUNNING_RECORDS";
    /** 仍有未解除的未知事实。 */
    public static final String GATE_UNRESOLVED_FACTS = "UNRESOLVED_FACTS";
    /** 实例归属不可证明（V34 未应用、缺归属行、实例被移除或改绑等）。 */
    public static final String GATE_EVIDENCE_NOT_PROVABLE = "EVIDENCE_NOT_PROVABLE";

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_UNKNOWN = "UNKNOWN";
    private static final String CLAIM_RELEASED = "RELEASED";
    private static final String CLAIM_COMPLETED = "COMPLETED";

    private static final String PATH_ADOPT_SUCCESS = "POST /api/runtime/unknown-executions/stuck-release/{releaseOrderId}/closure"
            + " (outcome=SUCCEEDED)";
    private static final String PATH_CLOSE_AS_FAILED = "POST /api/runtime/unknown-executions/stuck-release/{releaseOrderId}/closure"
            + " (outcome=FAILED)";
    private static final String PATH_RESOLVE_AS_UNKNOWN = "POST /api/runtime/unknown-executions/recover"
            + " (releaseOrderId)";
    private static final String PATH_RESOLVE_UNKNOWN_FACTS = "POST /api/runtime/unknown-executions/{id}/resolve"
            + " 或 /legacy/resolve (deployRecordId)";
    private static final String PATH_ABANDON_CLAIM = "POST /api/release-execution-runs/claims/{claimId}/abandon";

    private static final String NOTE_REMOTE_NOT_CHECKED =
            "平台不连接远端、不重试、不回滚，也不从超时或进程无响应推断远端已停止；"
                    + "本路径只按操作人的结论落库";
    private static final String NOTE_ABANDON_NOT_STOP =
            "放弃认领只表达“平台不再认为这次执行在飞”，**不**声明远端动作已经结束或已经停止，"
                    + "也不解除任何未解除的未知事实";

    /**
     * 判定输入：全部来自调用方在同一时刻读到的持久事实，本类不自己去查库。
     *
     * @param order                              发布单（要用锁后读回的那一行）
     * @param claim                              该发布单的部署认领行，不存在为 null
     * @param records                            该发布单本次执行的部署记录
     * @param unresolvedForOrder                 本发布单名下未解除的未知事实条数
     * @param unresolvedForServiceEnv            该服务/环境下未解除的未知事实条数
     * @param heldClaimIdOnServiceEnv            该服务/环境下仍为 HELD 的认领 ID（升序）
     * @param activeCanaryPlan                   该发布单是否还有未收敛的灰度计划
     * @param instanceAttributionProvable        实例归属是否可证明
     * @param instanceAttributionRejectionReason 不可证明时的理由
     * @param claimTableAvailable                V32 是否已应用
     * @param unknownMarkerTableAvailable        V31 是否已应用
     * @param mergeLedgerAvailable               V34 的合并台账是否已应用
     * @param now                                判定时刻（用于执行时间窗）
     */
    public record RouteInput(
            ReleaseOrder order,
            ReleaseExecutionRunClaim claim,
            List<RuntimeDeployRecord> records,
            int unresolvedForOrder,
            int unresolvedForServiceEnv,
            List<Long> heldClaimIdOnServiceEnv,
            boolean activeCanaryPlan,
            boolean instanceAttributionProvable,
            String instanceAttributionRejectionReason,
            boolean claimTableAvailable,
            boolean unknownMarkerTableAvailable,
            boolean mergeLedgerAvailable,
            OffsetDateTime now
    ) {
    }

    /** 判定结果：各路径的准入视图，以及按门槛键索引的**统一文案**。 */
    public record RouteAssessment(
            List<ReleaseClosureRouteView> routes,
            Map<String, String> gateMessages
    ) {

        /** 某道门槛的原因文案；键不存在时返回 null（调用方应把它当作不可达）。 */
        public String gateMessage(String gate) {
            return gateMessages.get(gate);
        }

        public List<ReleaseClosureRouteView> availableRoutes() {
            return routes.stream().filter(ReleaseClosureRouteView::isAvailable).toList();
        }

        public boolean anyRouteAvailable() {
            return routes.stream().anyMatch(ReleaseClosureRouteView::isAvailable);
        }

        public ReleaseClosureRouteView route(String route) {
            return routes.stream().filter(view -> route.equals(view.getRoute())).findFirst().orElse(null);
        }
    }

    /**
     * 判定全部路径。返回的 {@code gateMessages} 只在对应阻碍存在时才有条目，
     * 且其中指向其它入口的引导**只会指向该入口当前确实可用的路径**。
     */
    public RouteAssessment assess(RouteInput input) {
        List<RuntimeDeployRecord> records = input.records() == null ? List.of() : input.records();
        List<Long> heldClaimIds = input.heldClaimIdOnServiceEnv() == null
                ? List.of() : input.heldClaimIdOnServiceEnv();
        boolean orderRunning = input.order() != null
                && STATUS_RUNNING.equalsIgnoreCase(input.order().getStatus());
        List<RuntimeDeployRecord> runningRecords = records.stream()
                .filter(record -> STATUS_RUNNING.equalsIgnoreCase(record.getDeployStatus()))
                .toList();
        long unknownRecords = records.stream()
                .filter(record -> STATUS_UNKNOWN.equalsIgnoreCase(record.getDeployStatus()))
                .count();
        boolean heldClaim = !heldClaimIds.isEmpty();

        ReleaseClosureRouteView adoptSuccess = assessAdoptSuccess(input, orderRunning, runningRecords);
        ReleaseClosureRouteView closeAsFailed = assessCloseAsFailed(input, orderRunning, runningRecords);
        ReleaseClosureRouteView abandonClaim = assessAbandonClaim(input, heldClaimIds);
        ReleaseClosureRouteView resolveAsUnknown =
                assessResolveAsUnknown(input, orderRunning, heldClaim, runningRecords, unknownRecords);
        ReleaseClosureRouteView resolveFacts = assessResolveUnknownFacts(input);

        Map<String, String> gates = new LinkedHashMap<>();
        if (!orderRunning) {
            gates.put(GATE_ORDER_NOT_RUNNING, "发布单不在执行中（当前 "
                    + (input.order() == null ? "未知" : input.order().getStatus())
                    + "），无需收口，也不会覆盖已有终态");
        }
        if (input.activeCanaryPlan()) {
            gates.put(GATE_ACTIVE_CANARY_PLAN,
                    "该发布单还有未收敛的灰度计划：灰度发布由计划与批次自己的终态语义收口，人工收口不去替它下结论。"
                            + guidanceAfterCanary(resolveAsUnknown));
        }
        if (heldClaim) {
            gates.put(GATE_HELD_CLAIM, "同一服务/环境仍有 " + heldClaimIds.size()
                    + " 行未收口（HELD）的执行认领（认领 " + joinIds(heldClaimIds) + "），说明一次执行可能仍在进行。"
                    + (abandonClaim.isAvailable()
                    ? "请先在执行认领页面确认该次执行已不再由平台控制并显式放弃认领，再回来重试"
                    : "放弃认领入口当前不可用：" + joinReasons(abandonClaim) + "。")
                    + "（" + NOTE_ABANDON_NOT_STOP + "）");
        }
        String claimGuidance = input.claim() == null
                ? guidanceToRecovery(resolveAsUnknown, abandonClaim, heldClaimIds)
                : guidanceAfterClaimNotClosed(input.claim(), resolveAsUnknown, abandonClaim, heldClaimIds);
        if (input.claim() == null) {
            gates.put(GATE_CLAIM_MISSING, "该发布单没有可核对的部署认领，平台无法证明这次运行真正执行过部署，"
                    + "也无法排除直接发布仍在执行。" + claimGuidance);
        } else if (!isClaimProperlyClosed(input.claim())) {
            gates.put(GATE_CLAIM_NOT_CLOSED, "部署认领 " + input.claim().getId() + " 没有正常收口（状态 "
                    + input.claim().getStatus() + " / 释放结果 " + input.claim().getReleaseOutcome()
                    + "）。" + claimGuidance);
        }
        if (!runningRecords.isEmpty()) {
            gates.put(GATE_RUNNING_RECORDS, "发布单还有 " + runningRecords.size()
                    + " 条部署记录停在 RUNNING，说明执行尚未收口。" + guidanceToRecovery(resolveAsUnknown, abandonClaim, heldClaimIds));
        }
        if (input.unresolvedForOrder() > 0 || input.unresolvedForServiceEnv() > 0) {
            gates.put(GATE_UNRESOLVED_FACTS, "同一服务/环境仍存在未收口的执行事实（本单 "
                    + input.unresolvedForOrder() + " 条 / 服务环境 " + input.unresolvedForServiceEnv()
                    + " 条未解除的未知事实）：平台不据此判断远端状态，也不会在事实未收口时声明成功。"
                    + guidanceToResolveFacts(resolveFacts));
        }
        if (!input.instanceAttributionProvable()) {
            gates.put(GATE_EVIDENCE_NOT_PROVABLE,
                    evidenceGateMessage(input.instanceAttributionRejectionReason()));
        }

        return new RouteAssessment(List.of(adoptSuccess, closeAsFailed, resolveAsUnknown, resolveFacts, abandonClaim), gates);
    }

    /**
     * 实例归属不可证明时的统一文案。
     *
     * <p>由提交路径与对账共用：对账用只读证据算出理由，提交路径在锁内用 {@code collectUnderLock}
     * 的证据算出理由，两处的措辞必须一致，否则页面看到的失败原因与实际报错会不一样。
     */
    public static String evidenceGateMessage(String rejectionReason) {
        return "无法证明这次执行实际成功部署在哪些实例上，不能收口为成功："
                + (StringUtils.hasText(rejectionReason) ? rejectionReason : "实例归属不可证明");
    }

    // ------------------------------------------------------------------
    // 各路径
    // ------------------------------------------------------------------

    private ReleaseClosureRouteView assessAdoptSuccess(RouteInput input, boolean orderRunning,
                                                       List<RuntimeDeployRecord> runningRecords) {
        ReleaseClosureRouteView view = base(ReleaseClosureRouteView.ROUTE_ADOPT_SUCCESS, "人工收口为成功",
                PATH_ADOPT_SUCCESS, true);
        if (!orderRunning) {
            view.getBlockingReasons().add("发布单不在执行中，无需收口");
        }
        if (input.activeCanaryPlan()) {
            view.getBlockingReasons().add("还有未收敛的灰度计划，灰度发布不由人工收口下结论");
        }
        if (!input.heldClaimIdOnServiceEnv().isEmpty()) {
            view.getBlockingReasons().add("同一服务/环境仍有未收口（HELD）的执行认领");
        }
        if (input.claim() == null) {
            view.getBlockingReasons().add("没有部署认领，无法证明这次运行真正执行过部署");
        } else if (!isClaimProperlyClosed(input.claim())) {
            view.getBlockingReasons().add("部署认领没有正常收口（状态 " + input.claim().getStatus()
                    + " / 释放结果 " + input.claim().getReleaseOutcome() + "）");
        }
        if (!runningRecords.isEmpty()) {
            view.getBlockingReasons().add("还有 " + runningRecords.size() + " 条部署记录停在 RUNNING");
        }
        if (input.unresolvedForOrder() > 0 || input.unresolvedForServiceEnv() > 0) {
            view.getBlockingReasons().add("仍有未解除的未知事实（本单 " + input.unresolvedForOrder()
                    + " 条 / 服务环境 " + input.unresolvedForServiceEnv() + " 条）");
        }
        if (!input.instanceAttributionProvable()) {
            view.getBlockingReasons().add("实例归属不可证明"
                    + (StringUtils.hasText(input.instanceAttributionRejectionReason())
                    ? "：" + input.instanceAttributionRejectionReason() : ""));
        }
        view.setNote("接受时把订单收敛为 SUCCESS，并把实例版本只更新到本次执行产出的部署记录所证明的实例。"
                + NOTE_REMOTE_NOT_CHECKED);
        return finish(view);
    }

    private ReleaseClosureRouteView assessCloseAsFailed(RouteInput input, boolean orderRunning,
                                                        List<RuntimeDeployRecord> runningRecords) {
        ReleaseClosureRouteView view = base(ReleaseClosureRouteView.ROUTE_CLOSE_AS_FAILED, "人工收口为失败",
                PATH_CLOSE_AS_FAILED, true);
        if (!orderRunning) {
            view.getBlockingReasons().add("发布单不在执行中，无需收口");
        }
        if (input.activeCanaryPlan()) {
            view.getBlockingReasons().add("还有未收敛的灰度计划，灰度发布不由人工收口下结论");
        }
        if (!input.heldClaimIdOnServiceEnv().isEmpty()) {
            view.getBlockingReasons().add("同一服务/环境仍有未收口（HELD）的执行认领");
        }
        if (input.claim() == null) {
            view.getBlockingReasons().add("没有可核对的部署认领，无法排除直接发布仍在执行");
        } else if (!isClaimProperlyClosed(input.claim())) {
            view.getBlockingReasons().add("部署认领没有正常收口，放弃认领不能证明远端已结束");
        }
        if (!runningRecords.isEmpty()) {
            view.getBlockingReasons().add("还有 " + runningRecords.size()
                    + " 条部署记录停在 RUNNING：应先按“恢复为结果未知”留下未知事实，而不是直接给结论");
        }
        view.setNote("未解除的未知事实**不**阻止收口为失败，但也不会被解除；收口后阻断是否仍在由返回的 "
                + "blockingRemains 说明。本路径不改动任何实例版本。" + NOTE_REMOTE_NOT_CHECKED);
        return finish(view);
    }

    private ReleaseClosureRouteView assessResolveAsUnknown(RouteInput input, boolean orderRunning, boolean heldClaim,
                                                           List<RuntimeDeployRecord> runningRecords,
                                                           long unknownRecords) {
        ReleaseClosureRouteView view = base(ReleaseClosureRouteView.ROUTE_RESOLVE_AS_UNKNOWN, "恢复为结果未知",
                PATH_RESOLVE_AS_UNKNOWN, true);
        if (!input.unknownMarkerTableAvailable()) {
            view.getBlockingReasons().add("数据库表 runtime_execution_unknown 不可用（V31 未应用），"
                    + "该入口按失败关闭拒绝；需由数据库负责人应用迁移并重启服务");
            return finish(view);
        }
        if (!orderRunning) {
            view.getBlockingReasons().add("只有 RUNNING 发布单允许恢复为结果未知");
            return finish(view);
        }
        if (heldClaim) {
            view.getBlockingReasons().add("该服务/环境仍有一行正在执行（HELD）的部署认领，"
                    + "恢复入口要求先显式放弃认领");
            return finish(view);
        }
        List<RuntimeDeployRecord> records = input.records() == null ? List.of() : input.records();
        if (runningRecords.isEmpty()) {
            if (unknownRecords > 0) {
                view.setNote("部署记录已有 UNKNOWN，但订单没有收敛：恢复会补登记这些事实并把订单收敛为 FAILED；"
                        + "已 UNKNOWN 的记录不会被改写。");
                return finish(view);
            }
            if (records.isEmpty()) {
                view.getBlockingReasons().add("该发布单没有任何部署记录，恢复入口会以"
                        + "“不能证明远端操作未开始”拒绝，订单保持 RUNNING");
            } else {
                view.getBlockingReasons().add("部署记录都已是确定结果（没有 RUNNING / UNKNOWN），"
                        + "恢复入口会以“这不是结果未知”拒绝，不能借恢复改状态");
            }
            return finish(view);
        }
        List<RuntimeDeployRecord> notStale = new ArrayList<>();
        for (RuntimeDeployRecord record : runningRecords) {
            if (!isStale(record.getStartedAt(), input.now())) {
                notStale.add(record);
            }
        }
        if (!notStale.isEmpty()) {
            view.getBlockingReasons().add("有 " + notStale.size() + " 条部署记录仍在 "
                    + RecoveryWindow.MINUTES + " 分钟执行时间窗内（记录 "
                    + joinRecordIds(notStale) + "），恢复入口会以“不能判定为卡住”拒绝");
            view.setRetryableLater(true);
            view.setNote("等这些记录超出执行时间窗后重试即可；期间不需要改变任何本地事实。");
            return finish(view);
        }
        view.setNote("恢复只把仍为 RUNNING 的部署记录改成 UNKNOWN 并登记未解除事实，再把订单收敛为 FAILED；"
                + "它不发送任何信号、不重试、不回滚。这只表示系统记录了未知，不代表远端进程已停止。");
        return finish(view);
    }

    private ReleaseClosureRouteView assessResolveUnknownFacts(RouteInput input) {
        ReleaseClosureRouteView view = base(ReleaseClosureRouteView.ROUTE_RESOLVE_UNKNOWN_FACTS,
                "逐条解除未解除的未知事实", PATH_RESOLVE_UNKNOWN_FACTS, true);
        if (input.unresolvedForOrder() <= 0 && input.unresolvedForServiceEnv() <= 0) {
            view.getBlockingReasons().add("该服务/环境下没有未解除的未知事实，没有需要解除的对象");
            return finish(view);
        }
        if (!input.unknownMarkerTableAvailable()) {
            view.getBlockingReasons().add("解除登记依赖数据库表 runtime_execution_unknown（V31 未应用），"
                    + "已有 UNKNOWN 部署记录仍会挡住新操作，但平台当前无法解除它们；"
                    + "需由数据库负责人应用迁移并重启服务");
            return finish(view);
        }
        view.setNote("解除只写解除字段与审计，不改部署状态、不改订单状态、不重试、不连接远端。"
                + (input.mergeLedgerAvailable()
                ? "若某条订单级事实吸收了别的批次登记，页面会列出合并项并要求显式确认后才解除。"
                : "合并台账（V34）不可用：无法核实订单级事实名下是否吸收了其他批次，"
                + "页面会要求显式确认后才解除，且该确认不等于“没有合并”。"));
        return finish(view);
    }

    private ReleaseClosureRouteView assessAbandonClaim(RouteInput input, List<Long> heldClaimIds) {
        ReleaseClosureRouteView view = base(ReleaseClosureRouteView.ROUTE_ABANDON_CLAIM, "显式放弃 HELD 认领",
                PATH_ABANDON_CLAIM, true);
        if (!input.claimTableAvailable()) {
            view.getBlockingReasons().add("一次性执行认领表不可用（V32 未应用），不存在认领行，也就没有可放弃的对象");
            return finish(view);
        }
        if (heldClaimIds.isEmpty()) {
            view.getBlockingReasons().add("该服务/环境下没有仍为 HELD 的认领，没有需要放弃的对象");
            return finish(view);
        }
        view.setNote(NOTE_ABANDON_NOT_STOP + "。放弃后未知事实仍然独立阻断，直到被逐条解除。");
        return finish(view);
    }

    // ------------------------------------------------------------------
    // 统一文案
    // ------------------------------------------------------------------

    private static String guidanceAfterCanary(ReleaseClosureRouteView resolveAsUnknown) {
        return resolveAsUnknown.isAvailable()
                ? "请在灰度页面处理该计划，或按“恢复为结果未知”收口（该入口现在可用）后再收口订单。"
                : "请在灰度页面处理该计划；“恢复为结果未知”现在也不可用（"
                + joinReasons(resolveAsUnknown) + "）。";
    }

    private static String guidanceAfterClaimNotClosed(ReleaseExecutionRunClaim claim,
                                                     ReleaseClosureRouteView resolveAsUnknown,
                                                     ReleaseClosureRouteView abandonClaim,
                                                     List<Long> heldClaimIds) {
        String base = CLAIM_RELEASED.equals(claim.getStatus())
                ? "该认领已被释放，但释放结果是 " + claim.getReleaseOutcome() + "（不是正常收口）："
                + "放弃认领或异常释放都不声明远端已停止，平台不能据此给出结论。"
                : "该认领仍为 " + claim.getStatus() + "（未正常收口）。";
        return base + guidanceToRecovery(resolveAsUnknown, abandonClaim, heldClaimIds);
    }

    /**
     * 指向“恢复为结果未知”的引导。**只在它确实可用时**才写成引导；
     * 否则如实说明它为什么不可用、以及平台内是否还有别的入口。
     */
    private static String guidanceToRecovery(ReleaseClosureRouteView resolveAsUnknown,
                                             ReleaseClosureRouteView abandonClaim,
                                             List<Long> heldClaimIds) {
        if (resolveAsUnknown.isAvailable()) {
            return "请先按“恢复为结果未知”登记未知事实并人工核对（该入口现在可用），再收口订单。";
        }
        StringBuilder text = new StringBuilder("“恢复为结果未知”现在也不可用：")
                .append(joinReasons(resolveAsUnknown)).append("。");
        if (abandonClaim.isAvailable() && !heldClaimIds.isEmpty()) {
            text.append("该服务/环境仍有 HELD 认领（认领 ").append(joinIds(heldClaimIds))
                    .append("），可先显式放弃认领再重试恢复；放弃认领不代表远端已停止。");
        }
        if (resolveAsUnknown.isRetryableLater()) {
            text.append("这一条会随时间自行消失，等待后重试即可。");
        } else {
            text.append("平台内没有其他可执行入口：订单将保持 RUNNING，并继续挡住同一服务/环境的新发布、"
                    + "回滚与实例移除。请把这个死结交运维在平台之外处置。");
        }
        return text.toString();
    }

    private static String guidanceToResolveFacts(ReleaseClosureRouteView resolveFacts) {
        return resolveFacts.isAvailable()
                ? "请先逐条核对并解除这些事实。"
                : "解除入口现在不可用：" + joinReasons(resolveFacts) + "。";
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static ReleaseClosureRouteView base(String route, String label, String actionPath,
                                                boolean requiresRemoteManualCheck) {
        ReleaseClosureRouteView view = new ReleaseClosureRouteView();
        view.setRoute(route);
        view.setLabel(label);
        view.setActionPath(actionPath);
        view.setRequiresRemoteManualCheck(requiresRemoteManualCheck);
        view.setAvailable(true);
        return view;
    }

    private static ReleaseClosureRouteView finish(ReleaseClosureRouteView view) {
        if (!view.getBlockingReasons().isEmpty()) {
            view.setAvailable(false);
            // 不可用时不给入口：避免把操作者引到会被拒绝的路径。
            view.setActionPath(null);
        }
        return view;
    }

    private static boolean isClaimProperlyClosed(ReleaseExecutionRunClaim claim) {
        return CLAIM_RELEASED.equals(claim.getStatus()) && CLAIM_COMPLETED.equals(claim.getReleaseOutcome());
    }

    private static boolean isStale(OffsetDateTime startedAt, OffsetDateTime now) {
        if (startedAt == null || now == null) {
            return false;
        }
        return !startedAt.isAfter(now.minus(RecoveryWindow.DURATION));
    }

    private static String joinReasons(ReleaseClosureRouteView view) {
        return view.getBlockingReasons().isEmpty()
                ? "原因未记录" : String.join("；", view.getBlockingReasons());
    }

    private static String joinIds(List<Long> ids) {
        return ids.stream().filter(Objects::nonNull).map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String joinRecordIds(List<RuntimeDeployRecord> records) {
        List<Long> ids = records.stream().map(RuntimeDeployRecord::getId).filter(Objects::nonNull)
                .sorted(Comparator.naturalOrder()).toList();
        return joinIds(ids);
    }

    /** 人工恢复的执行时间窗。与 {@link RuntimeExecutionUnknownService} 的判定同源，避免两处漂移。 */
    private static final class RecoveryWindow {
        private static final Duration DURATION = RuntimeExecutionUnknownService.RECOVERY_STALE_AFTER;
        private static final long MINUTES = DURATION.toMinutes();
    }
}

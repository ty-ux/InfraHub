package com.infrahub.release.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrahub.common.context.UserContextHolder;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.dto.ApprovalRequestQuery;
import com.infrahub.release.dto.CreateApprovalRequest;
import com.infrahub.release.entity.ApprovalRequest;
import com.infrahub.release.mapper.ApprovalRequestMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.Map;

import static com.infrahub.release.service.MonitorAuditLogService.*;

@Service
public class ApprovalRequestService extends ServiceImpl<ApprovalRequestMapper, ApprovalRequest> {

    private final ApprovalPolicyService policyService;
    private final MonitorAuditLogService auditLogService;
    private final MonitorAlertRuleService alertRuleService;
    private final MonitorHealthCheckService healthCheckService;
    private final ReleaseOrderService releaseOrderService;
    private final ReleaseRollbackOrderService releaseRollbackOrderService;
    private final ObjectMapper objectMapper;
    private final ApprovalNotificationService notificationService;
    /**
     * 回滚目标协调器：回滚执行审批的创建入口必须与“目标修改 / 预检 / 执行”共用同一把回滚单行锁，
     * 否则 POST /api/approvals/requests 可绕过协调，直接在已换目标的回滚单上建出待处理审批。
     * 只依赖 Mapper 构成的协调器，不形成循环依赖，因此无需 @Lazy。
     */
    private final RollbackTargetCoordinator rollbackTargetCoordinator;
    /**
     * 发布执行认领协调：发布执行审批的创建入口必须与“直接发布 / 审批回调 / 流水线触发”共用同一套
     * 数据库短事务认领规则，否则 POST /api/approvals/requests 可绕过发布单的运行状态，
     * 在已有待处理执行审批或已在执行的发布单上再建一张审批。只依赖 Mapper，不形成循环依赖。
     */
    private final ReleaseExecutionClaimService releaseExecutionClaimService;

    /**
     * 自我代理引用：{@link #approve} 不能是事务入口（发布执行审批必须先把“批准 + 认领”提交，
     * 再在事务外调用外部执行器），但它需要调用两个事务方法——{@link #approveAtomically}
     * （保留其他审批业务原有的“批准 + 业务动作”同一事务语义）与
     * {@link #approveAndClaimReleaseExecution}（把发布执行审批的批准事实与订单认领放进同一短事务）。
     * 同类内部自调用不会经过 Spring 代理、@Transactional 会静默失效，因此注入自身代理再调用。
     *
     * <p>必须带 {@code @Lazy}：不带时字段注入阶段拿到的是尚未包装事务通知的原始对象，
     * 事务注解同样失效；带 {@code @Lazy} 时每次调用都从容器解析当前暴露的（已代理）实例。
     */
    @Autowired
    @Lazy
    private ApprovalRequestService selfProxy;

    @Autowired
    public ApprovalRequestService(
            ApprovalPolicyService policyService,
            MonitorAuditLogService auditLogService,
            @Lazy MonitorAlertRuleService alertRuleService,
            @Lazy MonitorHealthCheckService healthCheckService,
            @Lazy ReleaseOrderService releaseOrderService,
            @Lazy ReleaseRollbackOrderService releaseRollbackOrderService,
            ObjectMapper objectMapper,
            ApprovalNotificationService notificationService,
            RollbackTargetCoordinator rollbackTargetCoordinator,
            ReleaseExecutionClaimService releaseExecutionClaimService) {
        this.policyService = policyService;
        this.auditLogService = auditLogService;
        this.alertRuleService = alertRuleService;
        this.healthCheckService = healthCheckService;
        this.releaseOrderService = releaseOrderService;
        this.releaseRollbackOrderService = releaseRollbackOrderService;
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
        this.rollbackTargetCoordinator = rollbackTargetCoordinator;
        this.releaseExecutionClaimService = releaseExecutionClaimService;
    }

    /**
     * 审批创建前的目标协调，三个创建入口都必须经过。
     *
     * <p>回滚执行审批取回滚单行锁（目标冻结协议，保持不变）；发布执行审批取发布单统一认领协调：
     * 服务行锁 → 发布单行锁 → 重读事实 → 拒绝重复申请。检查与插入在同一短事务内完成，
     * 且都是数据库级协调，不依赖仅当前 JVM 有效的本地锁。
     */
    private void coordinateBeforeApprovalCreation(String bizType, Long bizId, String action) {
        lockRollbackOrderBeforeApproval(bizType, bizId, action);
        guardReleaseExecuteApprovalCreation(bizType, bizId, action);
    }

    /**
     * 回滚执行审批的创建前协调：先取回滚单行锁，再落待处理审批。
     * 目标修改入口取同一把锁并在发现待处理审批后拒绝变更，因此审批指向的目标与回滚单当前目标不会错位。
     */
    private void lockRollbackOrderBeforeApproval(String bizType, Long bizId, String action) {
        if (ApprovalRequest.BIZ_TYPE_ROLLBACK_ORDER.equalsIgnoreCase(bizType)
                && ApprovalRequest.ACTION_EXECUTE_ROLLBACK.equalsIgnoreCase(action)) {
            rollbackTargetCoordinator.lockRollbackOrder(bizId);
        }
    }

    /**
     * 发布执行审批的创建前协调：同一发布单已有待处理执行审批、正在执行（RUNNING）或已终结时，
     * 重复申请直接被拒绝，不产生新的审批、不产生“创建成功”审计、不产生通知草稿。
     */
    private void guardReleaseExecuteApprovalCreation(String bizType, Long bizId, String action) {
        if (ApprovalRequest.BIZ_TYPE_RELEASE_ORDER.equalsIgnoreCase(bizType)
                && ApprovalRequest.ACTION_EXECUTE_RELEASE.equalsIgnoreCase(action)) {
            releaseExecutionClaimService.assertApprovalCreationAllowed(bizId);
        }
    }

    /**
     * Create an approval request.
     * If no approval is required, returns null; otherwise creates a pending request.
     *
     * <p>回滚执行审批会先取得回滚单行锁，发布执行审批会先做统一认领协调
     * （见 {@link #coordinateBeforeApprovalCreation}），两者都需要事务边界：
     * 检查与插入必须在同一事务内完成，否则并发申请可以同时通过检查；
     * 其他业务类型的审批只是顺带获得原子性，语义不变。
     *
     * @return request ID if approval required, null otherwise
     */
    @Transactional
    public Long createRequest(CreateApprovalRequest request) {
        Long userId = UserContextHolder.getUserIdOrDefault(1L);

        // Check if approval is required
        if (!policyService.isApprovalRequired(request.getBizType(), request.getAction())) {
            return null;
        }

        coordinateBeforeApprovalCreation(request.getBizType(), request.getBizId(), request.getAction());

        // Create pending approval request
        ApprovalRequest approval = new ApprovalRequest();
        approval.setBizType(request.getBizType());
        approval.setBizId(request.getBizId());
        approval.setAction(request.getAction());
        approval.setStatus(ApprovalRequest.STATUS_PENDING);
        approval.setApplicantId(userId);
        approval.setApplyNote(request.getApplyNote());
        approval.setRequestPayload(request.getRequestPayload());
        approval.setCreatedAt(OffsetDateTime.now());
        save(approval);

        // Record audit log
        auditLogService.recordApproval(approval.getId(), ACTION_APPROVAL_CREATE, RESULT_SUCCESS,
                null,
                auditData("bizType", approval.getBizType(), "bizId", approval.getBizId(),
                        "action", approval.getAction(), "status", approval.getStatus()));

        // Send notification
        notificationService.notifyCreated(approval);

        return approval.getId();
    }

    /**
     * Create an approval request with payload (for system use).
     *
     * <p>回滚执行审批先取回滚单行锁，与目标修改入口互斥；发布执行审批先做统一认领协调，
     * 同一发布单已有待处理执行审批时拒绝重复申请。
     */
    @Transactional
    public Long createRequest(String bizType, Long bizId, String action, Long applicantId,
                              String applyNote, String requestPayload) {
        // Check if approval is required
        if (!policyService.isApprovalRequired(bizType, action)) {
            return null;
        }

        coordinateBeforeApprovalCreation(bizType, bizId, action);

        // Parse JSON string to Map
        Map<String, Object> payloadMap = parsePayloadToMap(requestPayload);

        ApprovalRequest approval = new ApprovalRequest();
        approval.setBizType(bizType);
        approval.setBizId(bizId);
        approval.setAction(action);
        approval.setStatus(ApprovalRequest.STATUS_PENDING);
        approval.setApplicantId(applicantId);
        approval.setApplyNote(applyNote);
        approval.setRequestPayload(payloadMap);
        approval.setCreatedAt(OffsetDateTime.now());
        save(approval);

        // Record audit log
        auditLogService.recordApproval(approval.getId(), ACTION_APPROVAL_CREATE, RESULT_SUCCESS,
                null,
                auditData("bizType", approval.getBizType(), "bizId", approval.getBizId(),
                        "action", approval.getAction(), "status", approval.getStatus()));

        // Send notification
        notificationService.notifyCreated(approval);

        return approval.getId();
    }

    /**
     * Create an approval request WITHOUT checking policy.
     * Used when admission service has already determined approval is required.
     * This bypasses ApprovalPolicyService.isApprovalRequired() check.
     *
     * <p>回滚执行审批同样先取回滚单行锁：准入策略分支（跳过策略检查）也必须受同一协调约束；
     * 发布执行审批同样受统一认领协调约束，不能因为跳过策略检查而绕过重复申请拦截。
     *
     * @return request ID (always creates the request)
     */
    @Transactional
    public Long createRequestUnchecked(String bizType, Long bizId, String action, Long applicantId,
                                        String applyNote, String requestPayload) {
        coordinateBeforeApprovalCreation(bizType, bizId, action);

        // Parse JSON string to Map
        Map<String, Object> payloadMap = parsePayloadToMap(requestPayload);

        ApprovalRequest approval = new ApprovalRequest();
        approval.setBizType(bizType);
        approval.setBizId(bizId);
        approval.setAction(action);
        approval.setStatus(ApprovalRequest.STATUS_PENDING);
        approval.setApplicantId(applicantId);
        approval.setApplyNote(applyNote);
        approval.setRequestPayload(payloadMap);
        approval.setCreatedAt(OffsetDateTime.now());
        save(approval);

        // Record audit log
        auditLogService.recordApproval(approval.getId(), ACTION_APPROVAL_CREATE, RESULT_SUCCESS,
                null,
                auditData("bizType", approval.getBizType(), "bizId", approval.getBizId(),
                        "action", approval.getAction(), "status", approval.getStatus()));

        // Send notification
        notificationService.notifyCreated(approval);

        return approval.getId();
    }

    /**
     * Parse JSON payload string to Map.
     */
    private Map<String, Object> parsePayloadToMap(String payload) {
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payload, Map.class);
        } catch (Exception e) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                "Payload 解析失败: " + payload);
        }
    }

    /**
     * Page query approval requests.
     */
    public Page<ApprovalRequest> page(ApprovalRequestQuery query) {
        LambdaQueryWrapper<ApprovalRequest> wrapper = new LambdaQueryWrapper<>();

        if (query.getBizType() != null && !query.getBizType().isBlank()) {
            wrapper.eq(ApprovalRequest::getBizType, query.getBizType());
        }
        if (query.getBizId() != null) {
            wrapper.eq(ApprovalRequest::getBizId, query.getBizId());
        }
        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            wrapper.eq(ApprovalRequest::getStatus, query.getStatus());
        }
        if (query.getApplicantId() != null) {
            wrapper.eq(ApprovalRequest::getApplicantId, query.getApplicantId());
        }
        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            wrapper.and(w -> w.like(ApprovalRequest::getApplyNote, query.getKeyword())
                    .or().like(ApprovalRequest::getApproveNote, query.getKeyword()));
        }

        wrapper.orderByDesc(ApprovalRequest::getCreatedAt);

        return page(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
    }

    /**
     * Approve an approval request and execute the business action.
     * Routes to appropriate business service based on bizType/action.
     *
     * <p>本方法自身不是事务入口，原因只有一个：发布执行审批必须先提交“批准事实”，
     * 再在事务外调用外部执行器。否则批准、发布单 RUNNING 与部署记录会一起挂在未提交的事务里，
     * 外部连接只能看到订单 CREATED、审批 PENDING、部署记录为空，直到 kubectl/SSH 返回才一次性可见。
     *
     * <p>非发布执行的审批业务（监控开关、回滚等）保持原有“批准 + 业务动作”同一事务的语义，
     * 经自我代理调用 {@link #approveAtomically} 完成。
     */
    public void approve(Long id, String approveNote) {
        ApprovalRequest approval = getRequiredApproval(id);
        ensurePendingStatus(approval);

        if (isReleaseExecuteApproval(approval)) {
            approveReleaseExecute(id, approveNote);
            return;
        }

        selfProxy.approveAtomically(id, approveNote);
    }

    /**
     * 发布执行审批：批准（PENDING → APPROVED）与执行认领（订单 → RUNNING）在同一个短事务内完成，
     * 该事务提交后才在事务外调用外部执行器。
     *
     * <p>两处状态同事务是为了消除“审批已 APPROVED、订单还没 RUNNING”的中间窗口：那种窗口里
     * 申请入口会看到待处理审批已经消失、订单仍是可执行状态，于是允许再建一张执行审批，
     * 同一发布单就能被启动两次。现在只要认领没成功，批准事实一并回滚，审批照旧是 PENDING。
     *
     * <p>批准事实提交后，即使后续执行失败或异常，审批也不会退回 PENDING；
     * 失败证据（审批失败审计、发布单 FAILED、部署记录失败日志）随各自的短事务落库。
     */
    private void approveReleaseExecute(Long id, String approveNote) {
        ReleaseExecutionClaimService.ClaimedExecution claimed = selfProxy.approveAndClaimReleaseExecution(id, approveNote);
        runBusinessAction(id, getRequiredApproval(id), claimed);
    }

    /**
     * 其他审批业务的事务化批准：批准与业务动作仍在同一事务内（业务动作失败时批准一起回滚，
     * 保持既有语义不变）。必须经 Spring 代理调用，否则 @Transactional 静默失效。
     */
    @Transactional
    public void approveAtomically(Long id, String approveNote) {
        assertTransactionActive("审批批准");
        ApprovalRequest approval = getRequiredApproval(id);
        ensurePendingStatus(approval);
        markApproved(id, approval, approveNote);
        // 发布执行审批不可能走到这里（approve 已按业务类型分流）；若被误调用，
        // executeBusinessAction 会因为缺少本次认领结果而拒绝执行，不会仅凭订单状态启动执行。
        runBusinessAction(id, approval, null);
    }

    /**
     * 发布执行审批的批准 + 认领：短事务，方法返回即提交（调用方必须经 Spring 代理调用本方法）。
     *
     * <p>两处状态由 {@link ReleaseExecutionClaimService#claimForApprovedExecution} 在同一把锁下
     * 用条件更新一次性落地：先按固定锁顺序（base_service → release_order）锁发布单并用加锁后读回的
     * 整行订单判定可执行性与服务/环境互斥，再消费审批。任何一步抢不到（订单状态已变、
     * 服务/环境已有执行中任务、审批已被并发批准/拒绝/取消）都抛异常回滚，
     * 不会留下“审批被消费却没有执行”或“订单 RUNNING 但审批仍 PENDING”的中间态。
     *
     * @return 本次成功认领的结果，只能来自本方法，供内部执行路径使用
     */
    @Transactional
    public ReleaseExecutionClaimService.ClaimedExecution approveAndClaimReleaseExecution(Long id, String approveNote) {
        assertTransactionActive("发布执行审批批准与执行认领");
        ApprovalRequest approval = getRequiredApproval(id);
        ensurePendingStatus(approval);
        // 失败关闭：本方法会消费审批并启动外部执行，只允许处理发布执行审批
        if (!isReleaseExecuteApproval(approval)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "非发布执行审批不能走批准并认领流程: " + id);
        }
        return releaseExecutionClaimService.claimForApprovedExecution(id, approval.getBizId(),
                UserContextHolder.getUserIdOrDefault(1L), approveNote);
    }

    /**
     * 条件更新消费审批：只有仍处于 PENDING 的审批行才能被改成 APPROVED。
     * 抢不到消费权（并发批准、已被拒绝或取消）时拒绝继续，因此同一审批最多启动一次业务动作。
     */
    private void markApproved(Long id, ApprovalRequest approval, String approveNote) {
        Long userId = UserContextHolder.getUserIdOrDefault(1L);
        OffsetDateTime approvedAt = OffsetDateTime.now();
        int updated = baseMapper.markApprovedIfPending(id, userId, approveNote, approvedAt);
        if (updated != 1) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "审批单状态不是PENDING，已被其他请求处理: " + id);
        }
        approval.setStatus(ApprovalRequest.STATUS_APPROVED);
        approval.setApproverId(userId);
        approval.setApproveNote(approveNote);
        approval.setApprovedAt(approvedAt);
    }

    /**
     * 执行业务动作并记录成功/失败审计与通知。失败时保留原始异常，不吞异常、不重试。
     *
     * <p>业务动作以受控失败返回时（发布执行器返回失败结果）同样抛出异常，因此与异常路径
     * 走同一条失败审计分支：只写失败审计、不发成功通知，已提交的批准事实与执行证据都不回滚。
     *
     * @param claimedExecution 发布执行审批本次成功认领的结果；其他审批业务为 null
     */
    private void runBusinessAction(Long id, ApprovalRequest approval,
                                   ReleaseExecutionClaimService.ClaimedExecution claimedExecution) {
        try {
            executeBusinessAction(approval, claimedExecution);
            auditLogService.recordApproval(id, ACTION_APPROVAL_APPROVE, RESULT_SUCCESS,
                    auditData("status", ApprovalRequest.STATUS_PENDING),
                    auditData("status", ApprovalRequest.STATUS_APPROVED, "bizId", approval.getBizId()));
            // Send notification after successful approval
            notificationService.notifyApproved(approval);
        } catch (Exception e) {
            // Business action failed, but approval is already APPROVED
            auditLogService.recordApproval(id, ACTION_APPROVAL_APPROVE, RESULT_FAILED,
                    auditData("status", ApprovalRequest.STATUS_PENDING),
                    auditData("status", ApprovalRequest.STATUS_APPROVED, "error", e.getMessage()));
            throw e;
        }
    }

    private ApprovalRequest getRequiredApproval(Long id) {
        ApprovalRequest approval = getById(id);
        if (approval == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "审批单不存在: " + id);
        }
        return approval;
    }

    private void ensurePendingStatus(ApprovalRequest approval) {
        if (!ApprovalRequest.STATUS_PENDING.equals(approval.getStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "审批单状态不是PENDING: " + approval.getStatus());
        }
    }

    private boolean isReleaseExecuteApproval(ApprovalRequest approval) {
        return ApprovalRequest.BIZ_TYPE_RELEASE_ORDER.equalsIgnoreCase(approval.getBizType())
                && ApprovalRequest.ACTION_EXECUTE_RELEASE.equalsIgnoreCase(approval.getAction());
    }

    /** 无活动事务时拒绝：行锁与条件更新都不会生效，绝不能假装已经协调。 */
    private void assertTransactionActive(String scene) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    scene + "未处于事务中，发布单行锁不会生效，已拒绝执行");
        }
    }

    /**
     * Execute business action based on bizType and action.
     *
     * @param claimedExecution 发布执行审批本次成功认领的结果；其他审批业务为 null
     */
    private void executeBusinessAction(ApprovalRequest approval,
                                       ReleaseExecutionClaimService.ClaimedExecution claimedExecution) {
        String bizType = approval.getBizType();
        String action = approval.getAction();
        Long bizId = approval.getBizId();
        Map<String, Object> params = approval.getRequestPayload();
        if (params == null) {
            params = Map.of();
        }

        switch (bizType) {
            case ApprovalRequest.BIZ_TYPE_MONITOR_ALERT_RULE:
                if (ApprovalRequest.ACTION_TOGGLE_RULE.equals(action)) {
                    Boolean enabled = (Boolean) params.get("enabled");
                    if (enabled == null) {
                        throw new BizException(ErrorCode.VALIDATION_ERROR,
                            "审批单缺少 enabled 参数");
                    }
                    alertRuleService.doToggleEnabled(bizId, enabled);
                }
                break;
            case ApprovalRequest.BIZ_TYPE_MONITOR_HEALTH_CHECK:
                if (ApprovalRequest.ACTION_TOGGLE_CHECK.equals(action)) {
                    Boolean enabled = (Boolean) params.get("enabled");
                    if (enabled == null) {
                        throw new BizException(ErrorCode.VALIDATION_ERROR,
                            "审批单缺少 enabled 参数");
                    }
                    healthCheckService.doToggleEnabled(bizId, enabled);
                }
                break;
            case ApprovalRequest.BIZ_TYPE_RELEASE_ORDER:
                if (ApprovalRequest.ACTION_EXECUTE_RELEASE.equals(action)) {
                    if (claimedExecution == null) {
                        // 失败关闭：发布执行只接受“本次成功认领”的结果，不能因为订单看起来是 RUNNING 就执行
                        throw new BizException(ErrorCode.VALIDATION_ERROR,
                            "发布执行缺少本次成功认领的结果，已拒绝执行: " + bizId);
                    }
                    if (!releaseOrderService.executeClaimedReleaseOrder(claimedExecution)) {
                        // 执行器返回失败结果（不是异常）：发布单与部署记录已经以 FAILED 落库，
                        // 批准事实也已提交，这里只把失败如实反馈给审批入口——不重试、不恢复、
                        // 不重新认领、不回退审批状态、不再次写终态，也不写成功审计和成功通知。
                        // 错误码沿用本执行路径既有的受控失败契约（BizException + 既有 ErrorCode），
                        // 不新增错误码；具体含义由消息说明。
                        throw new BizException(ErrorCode.VALIDATION_ERROR,
                            "审批已批准，但发布执行失败，请查看发布单和部署记录");
                    }
                }
                break;
            case ApprovalRequest.BIZ_TYPE_ROLLBACK_ORDER:
                if (ApprovalRequest.ACTION_EXECUTE_ROLLBACK.equals(action)) {
                    releaseRollbackOrderService.doExecuteRollbackOrder(bizId);
                }
                break;
            default:
                throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "未知的业务类型: " + bizType);
        }
    }

    /**
     * Reject an approval request.
     *
     * <p>拒绝是有条件的单步状态转换：只允许 PENDING → REJECTED，成功与否由数据库条件更新
     * （{@code WHERE status = 'PENDING'}）的受影响行数判定，不用“先读到的状态”做决定——
     * 读到 PENDING 之后、写回之前，并发批准可能已经消费掉这条审批，无条件 updateById
     * 会把已批准的审批覆盖成 REJECTED，被消费的审批就消失了。
     *
     * <p>抢不到转换权时受控失败：不写成功拒绝审计、不发通知，审批保持并发结果。
     */
    @Transactional
    public void reject(Long id, String approveNote) {
        ApprovalRequest approval = getById(id);
        if (approval == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "审批单不存在: " + id);
        }
        if (!ApprovalRequest.STATUS_PENDING.equals(approval.getStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "审批单状态不是PENDING: " + approval.getStatus());
        }

        Long userId = UserContextHolder.getUserIdOrDefault(1L);
        OffsetDateTime approvedAt = OffsetDateTime.now();
        int updated = baseMapper.markRejectedIfPending(id, userId, approveNote, approvedAt);
        if (updated != 1) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "审批单状态不是PENDING，已被其他请求处理: " + id);
        }
        approval.setStatus(ApprovalRequest.STATUS_REJECTED);
        approval.setApproverId(userId);
        approval.setApproveNote(approveNote);
        approval.setApprovedAt(approvedAt);

        // Record audit log
        auditLogService.recordApproval(id, ACTION_APPROVAL_REJECT, RESULT_SUCCESS,
                auditData("status", ApprovalRequest.STATUS_PENDING),
                auditData("status", ApprovalRequest.STATUS_REJECTED, "approveNote", approveNote));

        // Send notification
        notificationService.notifyRejected(approval);
    }

    /**
     * Cancel an approval request (by applicant).
     *
     * <p>取消同样是有条件的单步状态转换：只允许 PENDING → CANCELLED，由数据库条件更新的
     * 受影响行数判定成功，避免覆盖并发的批准/拒绝结果。
     *
     * <p>权限边界不变：只有申请人本人可以取消，先校验归属再做条件更新。
     */
    @Transactional
    public void cancel(Long id) {
        ApprovalRequest approval = getById(id);
        if (approval == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "审批单不存在: " + id);
        }
        if (!ApprovalRequest.STATUS_PENDING.equals(approval.getStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "审批单状态不是PENDING: " + approval.getStatus());
        }

        Long userId = UserContextHolder.getUserIdOrDefault(1L);
        if (!approval.getApplicantId().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权限取消他人的审批单");
        }

        if (baseMapper.markCancelledIfPending(id) != 1) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "审批单状态不是PENDING，已被其他请求处理: " + id);
        }
        approval.setStatus(ApprovalRequest.STATUS_CANCELLED);

        // Record audit log
        auditLogService.recordApproval(id, ACTION_APPROVAL_CANCEL, RESULT_SUCCESS,
                auditData("status", ApprovalRequest.STATUS_PENDING),
                auditData("status", ApprovalRequest.STATUS_CANCELLED));

        // Send notification
        notificationService.notifyCancelled(approval);
    }
}
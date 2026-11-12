package com.infrahub.release.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.dto.CreateHealthCheckRequest;
import com.infrahub.release.dto.MonitorHealthCheckQuery;
import com.infrahub.release.dto.UpdateHealthCheckRequest;
import com.infrahub.release.entity.MonitorHealthCheck;
import com.infrahub.release.mapper.MonitorHealthCheckMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

import static com.infrahub.release.service.MonitorAuditLogService.*;

@Service
@RequiredArgsConstructor
public class MonitorHealthCheckService extends ServiceImpl<MonitorHealthCheckMapper, MonitorHealthCheck> {

    private final MonitorAuditLogService auditLogService;
    private final ApprovalRequestService approvalRequestService;
    private final ApprovalPolicyService approvalPolicyService;
    private final MonitorTargetGuard monitorTargetGuard;

    public static final String BIZ_TYPE_MONITOR_HEALTH_CHECK = "MONITOR_HEALTH_CHECK";

    /**
     * 创建健康检查。实例型目标先与实例移除登记共用同一行锁并校验目标存在，再插入。
     */
    @Transactional
    public Long create(CreateHealthCheckRequest request) {
        monitorTargetGuard.requireInstanceTargetExists(request.getTargetType(), request.getTargetId());
        MonitorHealthCheck check = new MonitorHealthCheck();
        check.setTargetType(request.getTargetType());
        check.setTargetId(request.getTargetId());
        check.setCheckType(request.getCheckType());
        check.setCheckEndpoint(request.getCheckEndpoint());
        check.setExpectedResult(request.getExpectedResult());
        check.setTimeoutSeconds(request.getTimeoutSeconds());
        check.setEnabled(request.getEnabled());
        check.setCreatedAt(OffsetDateTime.now());
        save(check);

        // Record audit log
        auditLogService.recordHealthCheck(check.getId(), ACTION_CREATE_CHECK, RESULT_SUCCESS,
                null,
                auditData("checkId", check.getId(), "targetType", check.getTargetType(),
                        "targetId", check.getTargetId(), "checkType", check.getCheckType(),
                        "enabled", check.getEnabled()));

        return check.getId();
    }

    /**
     * 修改健康检查。新目标若是实例，同样先锁定实例行并校验存在，再写回。
     */
    @Transactional
    public void update(Long id, UpdateHealthCheckRequest request) {
        MonitorHealthCheck check = baseMapper.selectForUpdate(id);
        if (check == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "健康检查不存在: " + id);
        }
        monitorTargetGuard.requireInstanceTargetExists(request.getTargetType(), request.getTargetId());

        // Capture before state for audit
        Map<String, Object> beforeData = auditData("checkId", check.getId(), "targetType", check.getTargetType(),
                "targetId", check.getTargetId(), "checkType", check.getCheckType(),
                "enabled", check.getEnabled());

        check.setTargetType(request.getTargetType());
        check.setTargetId(request.getTargetId());
        check.setCheckType(request.getCheckType());
        check.setCheckEndpoint(request.getCheckEndpoint());
        check.setExpectedResult(request.getExpectedResult());
        check.setTimeoutSeconds(request.getTimeoutSeconds());
        check.setEnabled(request.getEnabled());
        updateById(check);

        // Record audit log
        auditLogService.recordHealthCheck(check.getId(), ACTION_UPDATE_CHECK, RESULT_SUCCESS,
                beforeData,
                auditData("checkId", check.getId(), "targetType", check.getTargetType(),
                        "targetId", check.getTargetId(), "checkType", check.getCheckType(),
                        "enabled", check.getEnabled()));
    }

    public void delete(Long id) {
        MonitorHealthCheck check = getById(id);
        if (check == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "健康检查不存在: " + id);
        }

        // Capture before state for audit
        Map<String, Object> beforeData = auditData("checkId", check.getId(), "enabled", check.getEnabled());

        removeById(id);

        // Record audit log
        auditLogService.recordHealthCheck(id, ACTION_DELETE_CHECK, RESULT_SUCCESS, beforeData, null);
    }

    @Transactional
    public Long toggleEnabled(Long id, Boolean enabled) {
        return toggleEnabled(id, enabled, null);
    }

    /**
     * Toggle health check enabled status.
     *
     * @param id        check ID
     * @param enabled   new enabled status
     * @param applyNote optional note for approval request
     * @return approval request ID if approval required, null if executed directly
     */
    @Transactional
    public Long toggleEnabled(Long id, Boolean enabled, String applyNote) {
        // Check if approval is required
        if (approvalPolicyService.isApprovalRequired(BIZ_TYPE_MONITOR_HEALTH_CHECK, "TOGGLE_CHECK")) {
            // Create approval request with payload containing the target enabled value
            Long userId = com.infrahub.common.context.UserContextHolder.getUserIdOrDefault(1L);
            String payload = "{\"enabled\":" + enabled + "}";
            Long approvalId = approvalRequestService.createRequest(
                    BIZ_TYPE_MONITOR_HEALTH_CHECK, id, "TOGGLE_CHECK", userId, applyNote, payload);

            if (approvalId != null) {
                // Approval required and created
                return approvalId;
            }
        }

        // No approval required, execute directly
        doToggleEnabled(id, enabled);
        return null;
    }

    /**
     * 实际启停实现（直接调用或审批回调）。
     *
     * <p>启用会把一条已存在的检查变成"有效引用"，因此先锁定实例行并校验目标存在；
     * 停用只是移除引用，不做校验，始终允许。
     */
    @Transactional
    public void doToggleEnabled(Long id, Boolean enabled) {
        MonitorHealthCheck check = baseMapper.selectForUpdate(id);
        if (check == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "健康检查不存在: " + id);
        }
        if (Boolean.TRUE.equals(enabled)) {
            monitorTargetGuard.requireInstanceTargetExists(check.getTargetType(), check.getTargetId());
        }

        Boolean previousEnabled = check.getEnabled();
        check.setEnabled(enabled);
        updateById(check);

        // Record audit log
        auditLogService.recordHealthCheck(id, ACTION_TOGGLE_CHECK, RESULT_SUCCESS,
                auditData("enabled", previousEnabled),
                auditData("enabled", enabled));
    }

    public Page<MonitorHealthCheck> page(MonitorHealthCheckQuery query) {
        LambdaQueryWrapper<MonitorHealthCheck> wrapper = new LambdaQueryWrapper<>();

        if (query.getTargetType() != null && !query.getTargetType().isBlank()) {
            wrapper.eq(MonitorHealthCheck::getTargetType, query.getTargetType());
        }
        if (query.getTargetId() != null) {
            wrapper.eq(MonitorHealthCheck::getTargetId, query.getTargetId());
        }
        if (query.getCheckType() != null && !query.getCheckType().isBlank()) {
            wrapper.eq(MonitorHealthCheck::getCheckType, query.getCheckType());
        }
        if (query.getEnabled() != null) {
            wrapper.eq(MonitorHealthCheck::getEnabled, query.getEnabled());
        }

        wrapper.orderByDesc(MonitorHealthCheck::getCreatedAt);

        return page(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
    }
}

package com.infrahub.release.controller;

import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.RecoverStuckExecutionRequest;
import com.infrahub.release.dto.RecoverStuckExecutionResponse;
import com.infrahub.release.dto.ResolveLegacyRuntimeExecutionUnknownRequest;
import com.infrahub.release.dto.ResolveRuntimeExecutionUnknownRequest;
import com.infrahub.release.dto.RuntimeExecutionUnknownResponse;
import com.infrahub.release.dto.StuckReleaseClosurePreviewResponse;
import com.infrahub.release.dto.StuckReleaseClosureRequest;
import com.infrahub.release.dto.StuckReleaseClosureResponse;
import com.infrahub.release.service.ReleaseStuckOrderClosureService;
import com.infrahub.release.service.RuntimeExecutionUnknownService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 结果未知执行的查询、人工解除与卡住执行恢复。
 *
 * <p>查询对所有具备发布查看权限的人开放，解除与恢复仅限 SYSTEM_ADMIN。
 * 所有写操作只改数据库登记/审计并收敛订单状态，不连接 SSH、不重试、不回滚。
 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/runtime/unknown-executions")
@PreAuthorize("hasAnyAuthority('RELEASE_VIEW','SYSTEM_ADMIN')")
public class RuntimeExecutionUnknownController {

    private final RuntimeExecutionUnknownService runtimeExecutionUnknownService;
    private final ReleaseStuckOrderClosureService releaseStuckOrderClosureService;

    @GetMapping
    public ApiResponse<List<RuntimeExecutionUnknownResponse>> list(
            @RequestParam(required = false) Long serviceId,
            @RequestParam(required = false) Long envId
    ) {
        return ApiResponse.success(
                runtimeExecutionUnknownService.listUnresolved(serviceId, envId),
                TraceIdHolder.get());
    }

    /**
     * 已解除的结果未知执行（历史存档，只读）。
     *
     * <p>与未解除列表分开，避免操作者把历史结论当成当前阻断；这里返回的 {@code resolutionOutcome}
     * 只是当时操作人给出的判断，不是系统对远端状态的探测结果。
     */
    @GetMapping("/resolved")
    public ApiResponse<List<RuntimeExecutionUnknownResponse>> listResolved(
            @RequestParam(required = false) Long serviceId,
            @RequestParam(required = false) Long envId
    ) {
        return ApiResponse.success(
                runtimeExecutionUnknownService.listResolved(serviceId, envId),
                TraceIdHolder.get());
    }

    @GetMapping("/{id}")
    public ApiResponse<RuntimeExecutionUnknownResponse> detail(@PathVariable Long id) {
        return ApiResponse.success(runtimeExecutionUnknownService.getById(id), TraceIdHolder.get());
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<RuntimeExecutionUnknownResponse> resolve(
            @PathVariable Long id,
            @Valid @RequestBody ResolveRuntimeExecutionUnknownRequest request
    ) {
        return ApiResponse.success(
                runtimeExecutionUnknownService.resolve(id, request.getOutcome(), request.getNote(),
                        Boolean.TRUE.equals(request.getAcknowledgeMergedFacts())),
                TraceIdHolder.get());
    }

    /** 解除历史 UNKNOWN 部署记录（还没有登记行的旧数据）。 */
    @PostMapping("/legacy/resolve")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<RuntimeExecutionUnknownResponse> resolveLegacy(
            @Valid @RequestBody ResolveLegacyRuntimeExecutionUnknownRequest request
    ) {
        return ApiResponse.success(
                runtimeExecutionUnknownService.resolveLegacy(
                        request.getDeployRecordId(), request.getOutcome(), request.getNote()),
                TraceIdHolder.get());
    }

    /** 把卡在 RUNNING 超过时间窗的发布单/回滚单收敛为结果未知。 */
    @PostMapping("/recover")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<RecoverStuckExecutionResponse> recover(
            @Valid @RequestBody RecoverStuckExecutionRequest request
    ) {
        boolean hasRelease = request.getReleaseOrderId() != null;
        boolean hasRollback = request.getRollbackOrderId() != null;
        if (hasRelease == hasRollback) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "releaseOrderId 与 rollbackOrderId 必须且只能填一个");
        }
        RecoverStuckExecutionResponse response = hasRelease
                ? runtimeExecutionUnknownService.recoverStuckRelease(
                        request.getReleaseOrderId(), request.getNote())
                : runtimeExecutionUnknownService.recoverStuckRollback(
                        request.getRollbackOrderId(), request.getNote());
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 收口前对账（只读）：订单 / 认领 / 流水线运行 / 部署记录 / 未知事实各是什么状态，
     * 以及“人工声明成功”与“人工声明失败”分别会不会被接受、被什么挡住。
     *
     * <p>预览不加锁，只作核对材料：它不是许可证，收口时会在服务行锁与订单行锁内重新判定。
     */
    @GetMapping("/stuck-release/{releaseOrderId}/closure-preview")
    public ApiResponse<StuckReleaseClosurePreviewResponse> previewClosure(@PathVariable Long releaseOrderId) {
        return ApiResponse.success(
                releaseStuckOrderClosureService.preview(releaseOrderId), TraceIdHolder.get());
    }

    /**
     * 受控人工收口一个卡住的发布单（仍是 RUNNING，但认领已释放、部署记录已是终态、流水线回调未完成）。
     *
     * <p>{@code outcome} 是操作人的结论。声明成功时平台核对本地证据（本次执行的记录全部成功、
     * 实例归属可验证、没有未解除的未知事实与 HELD 认领）；声明失败不解除任何未知事实，
     * 响应里的 blockingRemains 会说明阻断是否仍然存在。平台不连接远端、不重试、不回滚，
     * 也不从超时或进程无响应推断远端已停止。
     */
    @PostMapping("/stuck-release/{releaseOrderId}/closure")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<StuckReleaseClosureResponse> closeStuckRelease(
            @PathVariable Long releaseOrderId,
            @Valid @RequestBody StuckReleaseClosureRequest request
    ) {
        return ApiResponse.success(
                releaseStuckOrderClosureService.close(releaseOrderId, request.getOutcome(), request.getNote()),
                TraceIdHolder.get());
    }
}

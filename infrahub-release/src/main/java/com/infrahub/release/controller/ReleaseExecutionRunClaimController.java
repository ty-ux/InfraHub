package com.infrahub.release.controller;

import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.AbandonExecutionClaimRequest;
import com.infrahub.release.dto.ReleaseExecutionRunClaimResponse;
import com.infrahub.release.service.ReleaseExecutionRunClaimService;
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
 * 发布执行认领的查询与人工放弃。
 *
 * <p>查询对具备发布查看权限的人开放，放弃仅限 SYSTEM_ADMIN。放弃只把认领从 HELD 改成 RELEASED
 * 并写下原因与操作人：它不连接 SSH、不重试、不回滚，也不解除任何未解除的结果未知事实。
 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/release-execution-runs/claims")
@PreAuthorize("hasAnyAuthority('RELEASE_VIEW','SYSTEM_ADMIN')")
public class ReleaseExecutionRunClaimController {

    private final ReleaseExecutionRunClaimService releaseExecutionRunClaimService;

    @GetMapping
    public ApiResponse<List<ReleaseExecutionRunClaimResponse>> list(@RequestParam Long releaseOrderId) {
        return ApiResponse.success(
                releaseExecutionRunClaimService.listByReleaseOrderId(releaseOrderId).stream()
                        .map(ReleaseExecutionRunClaimResponse::fromEntity)
                        .toList(),
                TraceIdHolder.get());
    }

    /**
     * 放弃一条仍为 HELD 的认领（进程崩溃后没有任何线程会再释放它）。
     *
     * <p>只表达“平台不再认为这次执行在飞”。远端是否已经停止、这次执行到底有没有生效，
     * 仍然只能由人工核对后在结果未知页面给出结论。
     */
    @PostMapping("/{id}/abandon")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<ReleaseExecutionRunClaimResponse> abandon(
            @PathVariable Long id,
            @Valid @RequestBody AbandonExecutionClaimRequest request
    ) {
        return ApiResponse.success(
                ReleaseExecutionRunClaimResponse.fromEntity(
                        releaseExecutionRunClaimService.abandonClaim(id, request.getNote())),
                TraceIdHolder.get());
    }
}

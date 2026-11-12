package com.infrahub.release.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.api.PageResponse;
import com.infrahub.common.context.UserContextHolder;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.ApprovalRequestQuery;
import com.infrahub.release.dto.ApproveRequest;
import com.infrahub.release.dto.CreateApprovalRequest;
import com.infrahub.release.dto.UpsertApprovalPolicy;
import com.infrahub.release.entity.ApprovalPolicy;
import com.infrahub.release.entity.ApprovalRequest;
import com.infrahub.release.service.ApprovalPolicyService;
import com.infrahub.release.service.ApprovalRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalRequestService approvalRequestService;
    private final ApprovalPolicyService approvalPolicyService;

    /**
     * Create an approval request (or directly execute if no approval required).
     */
    @PostMapping("/requests")
    public ApiResponse<Long> createRequest(@RequestBody @Valid CreateApprovalRequest request) {
        Long approvalId = approvalRequestService.createRequest(request);
        if (approvalId == null) {
            // No approval required, return special code
            return ApiResponse.success(0L, TraceIdHolder.get());
        }
        return ApiResponse.success(approvalId, TraceIdHolder.get());
    }

    /**
     * Page query approval requests.
     */
    @GetMapping("/requests")
    @PreAuthorize("hasAnyAuthority('SYSTEM_ADMIN')")
    public ApiResponse<PageResponse<ApprovalRequest>> pageRequests(@Valid ApprovalRequestQuery query) {
        Page<ApprovalRequest> page = approvalRequestService.page(query);
        return ApiResponse.success(PageResponse.from(page), TraceIdHolder.get());
    }

    /**
     * Approve an approval request.
     */
    @PostMapping("/requests/{id}/approve")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Void> approve(
            @PathVariable Long id,
            @RequestBody(required = false) @Valid ApproveRequest request) {
        approvalRequestService.approve(id, request != null ? request.getApproveNote() : null);
        return ApiResponse.success(null, TraceIdHolder.get());
    }

    /**
     * Reject an approval request.
     */
    @PostMapping("/requests/{id}/reject")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Void> reject(
            @PathVariable Long id,
            @RequestBody @Valid ApproveRequest request) {
        approvalRequestService.reject(id, request.getApproveNote());
        return ApiResponse.success(null, TraceIdHolder.get());
    }

    /**
     * Cancel an approval request (by applicant).
     */
    @PostMapping("/requests/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable Long id) {
        approvalRequestService.cancel(id);
        return ApiResponse.success(null, TraceIdHolder.get());
    }

    /**
     * List all approval policies (including fixed supported policies not yet in database).
     * Only SYSTEM_ADMIN can view policies.
     */
    @GetMapping("/policies")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<java.util.List<ApprovalPolicy>> listPolicies() {
        java.util.List<ApprovalPolicy> policies = approvalPolicyService.listAllPolicies();
        return ApiResponse.success(policies, TraceIdHolder.get());
    }

    /**
     * Upsert an approval policy (create or update).
     * Only SYSTEM_ADMIN can manage policies.
     */
    @PutMapping("/policies")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<ApprovalPolicy> upsertPolicy(@RequestBody @Valid UpsertApprovalPolicy request) {
        ApprovalPolicy policy = approvalPolicyService.getPolicy(request.getBizType(), request.getAction());
        if (policy == null) {
            policy = new ApprovalPolicy();
            policy.setBizType(request.getBizType());
            policy.setAction(request.getAction());
        }
        policy.setEnabled(request.getEnabled());
        approvalPolicyService.saveOrUpdate(policy);
        return ApiResponse.success(policy, TraceIdHolder.get());
    }
}
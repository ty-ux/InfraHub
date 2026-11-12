package com.infrahub.release.controller;

import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.GovernanceEvidenceChain;
import com.infrahub.release.dto.GovernanceEvidenceChainQuery;
import com.infrahub.release.service.GovernanceEvidenceChainService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/governance")
public class GovernanceController {

    private final GovernanceEvidenceChainService evidenceChainService;

    @GetMapping("/evidence-chain")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceEvidenceChain> getEvidenceChain(@Valid GovernanceEvidenceChainQuery query) {
        GovernanceEvidenceChain chain = evidenceChainService.getEvidenceChain(query.getBizType(), query.getBizId());
        return ApiResponse.success(chain, TraceIdHolder.get());
    }
}

package com.infrahub.auth.controller;

import com.infrahub.auth.dto.AuthTokenResponse;
import com.infrahub.auth.dto.EmailAuthRequests;
import com.infrahub.auth.service.AccountInvitationService;
import com.infrahub.auth.service.AccountMail;
import com.infrahub.auth.service.EmailAccountService;
import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.support.TraceIdHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class EmailAuthController {
    private final AccountMail mail;
    private final EmailAccountService email;
    private final AccountInvitationService invitations;

    @GetMapping("/email/features")
    public ApiResponse<Features> features() {
        return ApiResponse.success(new Features(mail.enabled(), mail.invitationsEnabled()), TraceIdHolder.get());
    }

    @GetMapping("/email/settings")
    public ApiResponse<EmailAccountService.Settings> settings() {
        return ApiResponse.success(email.settings(), TraceIdHolder.get());
    }

    @PostMapping("/email/bind/request")
    public ApiResponse<Void> requestBinding(@Valid @RequestBody EmailAuthRequests.BindRequest request) {
        email.requestBinding(request);
        return ApiResponse.success(null, TraceIdHolder.get());
    }

    @PostMapping("/email/bind/confirm")
    public ApiResponse<AuthTokenResponse> confirmBinding(@Valid @RequestBody EmailAuthRequests.BindConfirm request) {
        return ApiResponse.success(email.confirmBinding(request), TraceIdHolder.get());
    }

    @PostMapping("/email/login/request")
    public ApiResponse<Void> requestLogin(@Valid @RequestBody EmailAuthRequests.Address request, HttpServletRequest http) {
        email.requestLoginCode(request.email(), http.getRemoteAddr());
        return ApiResponse.success(null, TraceIdHolder.get());
    }

    @PostMapping("/email/login")
    public ApiResponse<AuthTokenResponse> login(@Valid @RequestBody EmailAuthRequests.Code request, HttpServletRequest http) {
        return ApiResponse.success(email.loginWithCode(request, http.getRemoteAddr()), TraceIdHolder.get());
    }

    @PostMapping("/invitations")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Void> invite(@Valid @RequestBody EmailAuthRequests.Invite request) {
        invitations.invite(request);
        return ApiResponse.success(null, TraceIdHolder.get());
    }

    @PostMapping("/register")
    public ApiResponse<Void> register(@Valid @RequestBody EmailAuthRequests.Register request, HttpServletRequest http) {
        invitations.register(request, http.getRemoteAddr());
        return ApiResponse.success(null, TraceIdHolder.get());
    }

    public record Features(boolean emailCodes, boolean invitations) {
    }
}

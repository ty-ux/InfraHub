package com.infrahub.auth.controller;

import com.infrahub.auth.dto.AuthLoginRequest;
import com.infrahub.auth.dto.AuthTokenResponse;
import com.infrahub.auth.dto.ChangePasswordRequest;
import com.infrahub.auth.dto.CurrentUserResponse;
import com.infrahub.auth.service.AuthService;
import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.support.TraceIdHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ApiResponse<AuthTokenResponse> login(@Valid @RequestBody AuthLoginRequest request) {
        return ApiResponse.success(authService.login(request), TraceIdHolder.get());
    }

    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> me() {
        return ApiResponse.success(authService.currentUser(), TraceIdHolder.get());
    }

    @PostMapping("/password")
    public ApiResponse<AuthTokenResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        return ApiResponse.success(authService.changePassword(request), TraceIdHolder.get());
    }
}

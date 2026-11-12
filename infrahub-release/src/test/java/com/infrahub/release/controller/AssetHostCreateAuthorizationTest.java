package com.infrahub.release.controller;

import com.infrahub.common.api.ApiResponse;
import com.infrahub.release.dto.CreateAssetHostRequest;
import com.infrahub.release.entity.AssetHost;
import com.infrahub.release.service.AssetHostProfileService;
import com.infrahub.release.service.AssetHostService;
import com.infrahub.release.service.ResourceCapacityRiskService;
import com.infrahub.release.service.ResourceGovernanceActionPlanService;
import com.infrahub.release.service.ResourceGovernanceSuggestionService;
import com.infrahub.release.service.ResourcePoolService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 主机登记接口的权限验证：方法级鉴权真实生效，而不是"看起来有注解"。
 *
 * <p>做法是用最小 Spring 上下文开启 {@code @EnableMethodSecurity}，让 {@code @PreAuthorize}
 * 经真实的 AuthorizationManager 拦截器判定；调用方身份通过 SecurityContextHolder 注入。
 * 不启动 Web 容器、不连接数据库。
 */
class AssetHostCreateAuthorizationTest {

    private static final AssetHostService assetHostService = mock(AssetHostService.class);

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
        @Bean
        AssetController assetController() {
            return new AssetController(
                    mock(ResourcePoolService.class),
                    assetHostService,
                    mock(AssetHostProfileService.class),
                    mock(ResourceCapacityRiskService.class),
                    mock(ResourceGovernanceSuggestionService.class),
                    mock(ResourceGovernanceActionPlanService.class));
        }
    }

    private AnnotationConfigApplicationContext context;
    private AssetController controller;
    private CreateAssetHostRequest request;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(MethodSecurityTestConfig.class);
        controller = context.getBean(AssetController.class);

        request = new CreateAssetHostRequest();
        request.setHostname("app-01");
        request.setIp("192.0.2.3");
        request.setEnvId(2L);
        request.setOsType("LINUX");
        request.setHostType("VM");

        AssetHost created = new AssetHost();
        created.setId(99L);
        created.setHostStatus("UNKNOWN");
        created.setAgentStatus("UNKNOWN");
        when(assetHostService.createHost(any(CreateAssetHostRequest.class))).thenReturn(created);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        context.close();
        org.mockito.Mockito.reset(assetHostService);
    }

    private void authenticate(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "tester", "n/a", List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList()));
    }

    @Test
    @DisplayName("结构性守卫：登记方法必须带 SYSTEM_ADMIN 的 @PreAuthorize")
    void createHostCarriesSystemAdminPreAuthorize() throws Exception {
        Method method = AssetController.class.getMethod("createHost", CreateAssetHostRequest.class);
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertNotNull(preAuthorize, "createHost 必须带 @PreAuthorize，否则接口对任何登录用户开放");
        assertEquals("hasAuthority('SYSTEM_ADMIN')", preAuthorize.value());
    }

    @Test
    @DisplayName("未登录/无身份：调用被拒，且不会触达服务层")
    void rejectsAnonymousCaller() {
        // 完全无 Authentication 时，方法级鉴权抛 AuthenticationException；
        // 真实请求链上会由过滤器链兜底成 401/403，这里只断言"拒绝 + 不进服务层"。
        assertThrows(AuthenticationException.class, () -> controller.createHost(request));

        verify(assetHostService, never()).createHost(any(CreateAssetHostRequest.class));
    }

    @Test
    @DisplayName("已登录但无 SYSTEM_ADMIN：调用被拒，且不会触达服务层")
    void rejectsCallerWithoutSystemAdmin() {
        authenticate("RELEASE_VIEW");

        assertThrows(AccessDeniedException.class, () -> controller.createHost(request));

        verify(assetHostService, never()).createHost(any(CreateAssetHostRequest.class));
    }

    @Test
    @DisplayName("SYSTEM_ADMIN：调用放行并返回新建主机")
    void allowsSystemAdmin() {
        authenticate("SYSTEM_ADMIN");

        ApiResponse<AssetHost> response = controller.createHost(request);

        verify(assetHostService, times(1)).createHost(request);
        assertNotNull(response);
        assertEquals("SUCCESS", response.getCode());
        assertNotNull(response.getData());
        assertEquals(99L, response.getData().getId());
        assertEquals("UNKNOWN", response.getData().getHostStatus());
    }
}

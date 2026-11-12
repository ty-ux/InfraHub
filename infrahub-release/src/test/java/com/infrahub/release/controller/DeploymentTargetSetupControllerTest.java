package com.infrahub.release.controller;

import com.infrahub.release.service.DeploymentTargetSetupService;
import com.infrahub.release.dto.HostSshSetupRequest;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DeploymentTargetSetupControllerTest {
    @Configuration @EnableMethodSecurity static class SecurityConfig {
        @Bean DeploymentTargetSetupService service() { return mock(DeploymentTargetSetupService.class); }
        @Bean DeploymentTargetSetupController controller(DeploymentTargetSetupService service) { return new DeploymentTargetSetupController(service); }
    }
    @Test void realMethodSecurityRejectsReleaseViewerForAllSetupOperations() {
        try (var context = new AnnotationConfigApplicationContext(SecurityConfig.class)) {
            var controller = context.getBean(DeploymentTargetSetupController.class);
            var service = context.getBean(DeploymentTargetSetupService.class);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("viewer", "unused",
                    List.of(new SimpleGrantedAuthority("RELEASE_VIEW"))));
            assertThatThrownBy(() -> controller.getSsh(34L)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.saveSsh(34L, null)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.registerInstance(null)).isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(service);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", "unused",
                    List.of(new SimpleGrantedAuthority("SYSTEM_ADMIN"))));
            controller.getSsh(34L); verify(service).getSsh(34L);
        } finally { SecurityContextHolder.clearContext(); }
    }
    @Test void httpContractValidatesSshPortAndRequiredInstanceRelationships() throws Exception {
        var service = mock(DeploymentTargetSetupService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new DeploymentTargetSetupController(service)).build();
        mvc.perform(put("/api/assets/hosts/34/ssh-config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"root\",\"sshPort\":0,\"privateKeySecretRefId\":11,\"enabled\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/runtime/service-instances").contentType(MediaType.APPLICATION_JSON)
                .content("{\"serviceId\":15,\"envId\":30,\"instanceName\":\"test\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        mvc.perform(put("/api/assets/hosts/34/ssh-config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"root\",\"sshPort\":22,\"privateKeySecretRefId\":11,\"enabled\":true}"))
                .andExpect(status().isOk());
        verify(service).saveSsh(eq(34L), any(HostSshSetupRequest.class));
    }
}

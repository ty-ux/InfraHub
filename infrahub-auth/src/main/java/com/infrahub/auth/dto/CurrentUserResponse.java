package com.infrahub.auth.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.Set;

@Data
@Builder
public class CurrentUserResponse {

    private Long userId;
    private String username;
    private String displayName;
    private boolean mustChangePassword;
    private Set<String> authorities;
    private Set<String> allAppPermissions;
    private Map<String, Set<Long>> applicationPermissions;
}

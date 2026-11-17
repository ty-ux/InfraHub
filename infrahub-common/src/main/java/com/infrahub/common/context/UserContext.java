package com.infrahub.common.context;

import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.Set;

@Data
@Builder
public class UserContext {

    private Long userId;
    private String username;
    private String displayName;
    private Set<String> authorities;
    @Builder.Default
    private Set<String> allAppPermissions = Set.of();
    @Builder.Default
    private Map<String, Set<Long>> applicationPermissions = Map.of();
}

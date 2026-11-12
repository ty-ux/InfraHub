package com.infrahub.common.context;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@Builder
public class UserContext {

    private Long userId;
    private String username;
    private String displayName;
    private Set<String> authorities;
}

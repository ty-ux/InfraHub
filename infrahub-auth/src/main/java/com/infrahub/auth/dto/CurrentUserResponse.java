package com.infrahub.auth.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@Builder
public class CurrentUserResponse {

    private Long userId;
    private String username;
    private String displayName;
    private Set<String> authorities;
}

package com.infrahub.release.dto;

import lombok.Data;

import java.util.Map;

@Data
public class FailActionExecutionRequest {

    private String failureReason;
    private Map<String, Object> evidencePayload;
}
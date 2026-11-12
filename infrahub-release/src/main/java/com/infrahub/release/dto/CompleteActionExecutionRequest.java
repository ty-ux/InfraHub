package com.infrahub.release.dto;

import lombok.Data;

import java.util.Map;

@Data
public class CompleteActionExecutionRequest {

    private String resultSummary;
    private Map<String, Object> evidencePayload;
}
package com.infrahub.release.dto;

import lombok.Data;

import java.util.Map;

@Data
public class PrecheckSuggestionItem {

    private String suggestionType;
    private String title;
    private String description;
    private String targetRoute;
    private Map<String, Object> targetParams;
}

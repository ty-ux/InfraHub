package com.infrahub.release.dto;

import lombok.Data;

@Data
public class VersionCompareItem {

    private String fieldName;
    private String currentValue;
    private String targetValue;
    private Boolean changed;
}

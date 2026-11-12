package com.infrahub.release.dto;

import lombok.Data;

@Data
public class PrecheckFailureCategoryItem {

    private String category;
    private Integer count;
    private String message;
}

package com.infrahub.release.dto;

import lombok.Data;

@Data
public class DatabaseSequenceHealth {

    private String tableName;
    private String sequenceName;
    private Long maxId;
    private Long sequenceValue;
    private boolean healthy;
    private String suggestedFixSql;
}

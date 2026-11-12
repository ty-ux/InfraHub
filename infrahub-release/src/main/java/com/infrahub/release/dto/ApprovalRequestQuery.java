package com.infrahub.release.dto;

import com.infrahub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ApprovalRequestQuery extends PageQuery {

    private String bizType;
    private Long bizId;
    private String status;
    private Long applicantId;
    private String keyword;
}
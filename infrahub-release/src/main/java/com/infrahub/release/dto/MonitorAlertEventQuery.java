package com.infrahub.release.dto;

import com.infrahub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class MonitorAlertEventQuery extends PageQuery {

    private Long eventId;
    private String targetType;
    private Long targetId;
    private String alertStatus;
    private Long ruleId;
}

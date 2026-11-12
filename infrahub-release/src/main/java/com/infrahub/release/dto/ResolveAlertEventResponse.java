package com.infrahub.release.dto;

import lombok.Data;

/**
 * Response for manually resolving an alert event.
 */
@Data
public class ResolveAlertEventResponse {

    private Long eventId;
    private String alertStatus;
    private String recoveredAt;
    private Long handleBy;
    private String handleNote;
}

package com.infrahub.release.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request for manually resolving an alert event.
 */
@Data
public class ResolveAlertEventRequest {

    /**
     * Optional handling note (max 512 chars per DB constraint).
     */
    @Size(max = 512, message = "备注最长512个字符")
    private String handleNote;
}

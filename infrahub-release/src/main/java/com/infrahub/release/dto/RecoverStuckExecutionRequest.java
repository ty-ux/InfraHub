package com.infrahub.release.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 卡住的执行的人工恢复请求：releaseOrderId 与 rollbackOrderId 只能填一个。
 * 本操作不连接任何目标主机，只把仍为 RUNNING 的部署记录登记为 UNKNOWN，并让订单收敛为 FAILED。
 */
@Data
public class RecoverStuckExecutionRequest {

    private Long releaseOrderId;
    private Long rollbackOrderId;

    @NotBlank
    @Size(max = 512)
    private String note;
}

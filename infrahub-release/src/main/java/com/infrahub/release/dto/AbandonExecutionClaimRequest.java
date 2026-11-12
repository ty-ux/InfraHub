package com.infrahub.release.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 放弃一条仍然 HELD 的发布执行认领。
 *
 * <p>{@code note} 是操作人对“为什么这次执行不再由平台控制”的说明，会写进认领行的
 * {@code release_reason} 与审计。它**不是**对远端状态的结论：放弃认领只说明平台不再把这次执行
 * 视为在飞，不代表远端进程已经停止，也不会解除任何未解除的结果未知事实。
 */
@Data
public class AbandonExecutionClaimRequest {

    @NotBlank
    @Size(max = 512)
    private String note;
}

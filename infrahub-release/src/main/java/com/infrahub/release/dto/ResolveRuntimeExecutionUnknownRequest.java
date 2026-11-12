package com.infrahub.release.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ResolveRuntimeExecutionUnknownRequest {

    /** REMOTE_EFFECT_APPLIED / REMOTE_EFFECT_NOT_APPLIED / REMOTE_EFFECT_PARTIAL。 */
    @NotBlank
    private String outcome;

    /** 人工核对结论与依据；不保存凭据或远端输出。 */
    @NotBlank
    @Size(max = 1000)
    private String note;

    /**
     * 显式确认“解除会同时放行这条事实名下被合并的其他批次登记”。
     *
     * <p>去重粒度决定同一发布单下不同批次的订单级登记可能落到同一行（V31 索引不含 plan/batch）。
     * 这条事实名下已有留证的合并、或合并台账不可核实（V34 未应用）时，未确认会被拒绝；
     * 确认本身会写进审计。默认不确认，避免前端漏传时静默放行别的批次。
     */
    private Boolean acknowledgeMergedFacts;
}

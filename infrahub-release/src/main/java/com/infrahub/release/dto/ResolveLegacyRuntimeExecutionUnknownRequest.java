package com.infrahub.release.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 解除历史 UNKNOWN 部署记录：该记录还没有登记行，解除时补一条已解除的审计行。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ResolveLegacyRuntimeExecutionUnknownRequest extends ResolveRuntimeExecutionUnknownRequest {

    @NotNull
    private Long deployRecordId;
}

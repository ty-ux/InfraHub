package com.infrahub.release.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 回滚单目标版本更新请求：仅接受目标版本 ID。
 * 来源发布单、状态、原版本、创建人等字段不通过该接口修改。
 */
@Data
public class UpdateRollbackTargetRequest {

    @NotNull(message = "目标版本不能为空")
    private Long toVersionId;
}
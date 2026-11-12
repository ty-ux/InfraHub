package com.infrahub.release.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 卡住发布单的受控人工收口请求。
 *
 * <p>{@code outcome} 是**操作人的结论**，不是平台的探测结果：平台不连接远端、不重试、不回滚，
 * 也不从超时或有进程无响应推断远端已经停止。平台只做两件事：核对本地的持久事实是否支持这个结论，
 * 以及把结论与依据写进审计。
 */
@Data
public class StuckReleaseClosureRequest {

    /** SUCCEEDED：本次发布实际成功；FAILED：本次发布没有成功。 */
    @NotBlank
    private String outcome;

    /** 人工核对结论与依据（核对了什么、依据来自哪里）；不保存凭据或远端输出。 */
    @NotBlank
    @Size(max = 1000)
    private String note;
}

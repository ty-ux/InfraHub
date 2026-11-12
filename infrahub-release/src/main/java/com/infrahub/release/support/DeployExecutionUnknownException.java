package com.infrahub.release.support;

/**
 * 部署执行已经开始、但无法确认结果时抛出的异常。
 *
 * <p>{@link com.infrahub.release.service.ReleaseDeployExecutionService} 在已经提交过 RUNNING
 * 部署记录、并且已进入外部调用之后遇到未预期异常时，用它把“结果未知”与“执行前校验失败”区分开：
 * 前者不能报成确定失败，也不能当作什么都没发生。调用方应据此把发布单/批次/部署记录收敛为
 * {@link DeployExecutionOutcome#UNKNOWN}，而不是 FAILED。
 */
public class DeployExecutionUnknownException extends RuntimeException {

    public DeployExecutionUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}

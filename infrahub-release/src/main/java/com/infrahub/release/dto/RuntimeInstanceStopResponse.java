package com.infrahub.release.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 单实例停止请求的结果。
 *
 * <p><b>outcome 是结论的唯一来源</b>，调用方不得用 HTTP 成功去推断"已停止"：
 * <ul>
 *   <li>{@code IDENTITY_UNCONFIRMED} —— 无法证明目标进程就是该实例，<b>未发送任何信号</b>（当前可达）；</li>
 *   <li>{@code REFUSED} —— 请求被前置条件拒绝（登记不完整、身份不可信、自停不可排除），未发送信号（当前可达）；</li>
 *   <li>{@code UNSUPPORTED} —— 实例类型不由平台停止（当前可达）；</li>
 *   <li>{@code FAILED} —— 已尝试但失败，是否发出信号以文案为准（当前不可达）；</li>
 *   <li>{@code UNKNOWN} —— 超时或中断，结果未知（当前不可达）；</li>
 *   <li>{@code STOPPED} —— 已观察到指定实例退出（仅当将来接入可信执行者后才可能出现）。</li>
 * </ul>
 *
 * <p>{@code reasonCode} 给出上述结论的细分原因（固定枚举，不含配置值），供界面、审计与独立验证使用；
 * 它不改变结论，也不代表已发送信号。
 *
 * <p>当前实现只返回 {@code IDENTITY_UNCONFIRMED}：本类刻意不携带远端输出、PID、命令行或其他
 * 不可控文本，避免把服务器返回的内容带进界面、日志或审计。
 */
@Data
public class RuntimeInstanceStopResponse {

    private Long instanceId;
    private String instanceName;
    private Long serviceId;
    private Long envId;
    private Long hostId;
    private String instanceType;

    /** 见类注释；这是唯一可据以判断"是否已停止"的字段 */
    private String outcome;
    /** 结论的细分原因（固定枚举），不改变 outcome，也不代表已发送信号 */
    private String reasonCode;
    /** 面向使用者的结论说明；固定文案，不含配置值、命令或远端输出 */
    private String message;

    private OffsetDateTime checkedAt;
}

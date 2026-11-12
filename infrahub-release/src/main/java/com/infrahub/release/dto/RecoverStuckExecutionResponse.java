package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 人工恢复卡住执行的结果摘要；不含远端输出或命令。 */
@Data
@AllArgsConstructor
public class RecoverStuckExecutionResponse {

    /** RELEASE / ROLLBACK。 */
    private String targetType;
    private Long targetId;
    private int runningRecordsRecovered;
    private int unknownRecordsRegistered;
    private String message;
}

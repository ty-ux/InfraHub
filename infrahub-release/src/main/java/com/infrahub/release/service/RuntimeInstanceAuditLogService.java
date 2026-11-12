package com.infrahub.release.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrahub.common.context.UserContextHolder;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.mapper.SysAuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 运行实例生命周期操作的审计记录。
 *
 * <p>审计内容只含身份、时间与结果：写入的是 PID、状态、计数这类可复核事实，
 * <b>不含</b>密码、私钥、SSH 命令或部署命令原文。
 */
@Service
@RequiredArgsConstructor
public class RuntimeInstanceAuditLogService {

    public static final String BIZ_TYPE_RUNTIME_INSTANCE = "RUNTIME_INSTANCE";
    public static final String ACTION_STOP_INSTANCE = "STOP_INSTANCE";
    public static final String ACTION_DEREGISTER_INSTANCE = "DEREGISTER_INSTANCE";

    private final SysAuditLogMapper sysAuditLogMapper;
    private final ObjectMapper objectMapper;

    public void record(Long instanceId,
                       String action,
                       String result,
                       Map<String, ?> beforeData,
                       Map<String, ?> afterData) {
        sysAuditLogMapper.insertAuditLog(
                UserContextHolder.getUserIdOrDefault(1L),
                BIZ_TYPE_RUNTIME_INSTANCE,
                instanceId,
                action,
                toJson(beforeData),
                toJson(afterData),
                result,
                null
        );
    }

    private String toJson(Map<String, ?> value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "审计日志序列化失败");
        }
    }
}

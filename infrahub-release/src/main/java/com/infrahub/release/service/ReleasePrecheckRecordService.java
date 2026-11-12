package com.infrahub.release.service;

import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.entity.ReleasePrecheckRecord;
import com.infrahub.release.mapper.ReleasePrecheckRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 将真实前置检查结果汇总写入 release_precheck_record。
 *
 * <p>每次调用只执行一次 INSERT，不支持 update/upsert；全部实例成功才记录 success=true。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReleasePrecheckRecordService {

    private static final String BIZ_TYPE_RELEASE_ORDER = "RELEASE_ORDER";
    private static final String BIZ_TYPE_ROLLBACK_ORDER = "ROLLBACK_ORDER";

    private static final int SUMMARY_MAX_LENGTH = 500;

    /** 常见敏感键值形式（password/passwd/token/authorization/secret/privateKey/private_key，支持 = 或 : 分隔，含可选 Bearer 前缀） */
    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            "(?i)(password|passwd|token|authorization|secret|privatekey|private_key)\\s*[=:]\\s*(?:bearer\\s+)?[^\\s,;]+");

    private final ReleasePrecheckRecordMapper releasePrecheckRecordMapper;

    public void recordPrecheck(String bizType, Long bizId, Long envId, Long serviceId,
                               List<PrecheckInstanceOutcome> results) {
        if (!BIZ_TYPE_RELEASE_ORDER.equals(bizType) && !BIZ_TYPE_ROLLBACK_ORDER.equals(bizType)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "不支持的前置检查业务类型: " + bizType);
        }
        if (results == null || results.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "前置检查实例结果不能为空");
        }

        int total = results.size();
        int successCount = (int) results.stream().filter(PrecheckInstanceOutcome::success).count();
        boolean allSuccess = successCount == total;
        String firstFailureReason = results.stream()
                .filter(outcome -> !outcome.success())
                .map(outcome -> sanitize(outcome.failureReason()))
                .filter(reason -> reason != null && !reason.isBlank())
                .findFirst()
                .orElse(null);

        ReleasePrecheckRecord record = new ReleasePrecheckRecord();
        record.setBizType(bizType);
        record.setBizId(bizId);
        record.setEnvId(envId);
        record.setServiceId(serviceId);
        record.setSuccess(allSuccess);
        record.setSummary(buildSummary(total, successCount, firstFailureReason));
        releasePrecheckRecordMapper.insert(record);
    }

    private String buildSummary(int total, int successCount, String firstFailureReason) {
        String summary = String.format("前置检查实例 %d/%d 通过；失败 %d；首条失败原因: %s",
                successCount, total, total - successCount,
                firstFailureReason != null ? firstFailureReason : "无");
        return truncate(collapseWhitespace(summary));
    }

    /** 空白压缩为单行，并截断至 SUMMARY_MAX_LENGTH */
    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        String singleLine = text.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= SUMMARY_MAX_LENGTH ? singleLine : singleLine.substring(0, SUMMARY_MAX_LENGTH);
    }

    private String collapseWhitespace(String text) {
        if (text == null) {
            return null;
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    /** 脱敏敏感键值形式；保留原文结构，仅遮蔽值 */
    private String sanitize(String text) {
        if (text == null) {
            return null;
        }
        String masked = SENSITIVE_PATTERN.matcher(text).replaceAll("$1=***");
        return collapseWhitespace(masked);
    }

    public record PrecheckInstanceOutcome(boolean success, String failureReason) {
    }
}
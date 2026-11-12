package com.infrahub.release.service;

import com.infrahub.release.dto.NotificationRecordResponse;
import com.infrahub.release.entity.MonitorNotificationRecord;
import com.infrahub.release.mapper.MonitorNotificationRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * E3.2: 通知记录查询服务
 */
@Service
@RequiredArgsConstructor
public class MonitorNotificationRecordService {

    private final MonitorNotificationRecordMapper recordMapper;

    public List<NotificationRecordResponse> getByAlertEventId(Long eventId) {
        List<MonitorNotificationRecord> records = recordMapper.findByAlertEventId(eventId);
        return records.stream()
                .map(NotificationRecordResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public long countByAlertEventId(Long eventId) {
        return recordMapper.countByAlertEventId(eventId);
    }

    public List<NotificationRecordResponse> getByRuleId(Long ruleId, int limit) {
        List<MonitorNotificationRecord> records = recordMapper.findByRuleId(ruleId, limit);
        return records.stream()
                .map(NotificationRecordResponse::fromEntity)
                .collect(Collectors.toList());
    }
}
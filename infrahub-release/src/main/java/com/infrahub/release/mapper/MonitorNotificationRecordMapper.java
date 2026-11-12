package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.release.entity.MonitorNotificationRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MonitorNotificationRecordMapper extends BaseMapper<MonitorNotificationRecord> {

    @Select("""
            SELECT * FROM monitor_notification_record
            WHERE alert_event_id = #{eventId}
            ORDER BY created_at DESC
            """)
    List<MonitorNotificationRecord> findByAlertEventId(@Param("eventId") Long eventId);

    @Select("""
            SELECT COUNT(1) FROM monitor_notification_record
            WHERE alert_event_id = #{eventId}
            """)
    long countByAlertEventId(@Param("eventId") Long eventId);

    @Select("""
            SELECT * FROM monitor_notification_record
            WHERE alert_rule_id = #{ruleId}
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    List<MonitorNotificationRecord> findByRuleId(@Param("ruleId") Long ruleId, @Param("limit") int limit);
}
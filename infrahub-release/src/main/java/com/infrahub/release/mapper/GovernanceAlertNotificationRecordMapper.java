package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.release.entity.GovernanceAlertNotificationRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 告警通知记录 Mapper
 * Contract: v2-stage-q-contract.md Section 9.1
 * S 阶段统计查询: v2-stage-s-contract.md Section 5
 */
@Mapper
public interface GovernanceAlertNotificationRecordMapper extends com.baomidou.mybatisplus.core.mapper.BaseMapper<GovernanceAlertNotificationRecord> {

    /**
     * 获取下一个通知编号序号
     */
    @Select("SELECT nextval('seq_notification_record_no')")
    Long getNextNotificationNoSequence();

    /**
     * 按条件分页查询通知记录
     * Contract: v2-stage-q-contract.md Section 2.3
     */
    @Select("""
            <script>
            SELECT * FROM release_governance_alert_notification_record
            WHERE deleted = 0
            <if test="alertRecordId != null">
                AND alert_record_id = #{alertRecordId}
            </if>
            <if test="channelType != null and channelType != ''">
                AND channel_type = #{channelType}
            </if>
            <if test="notificationStatus != null and notificationStatus != ''">
                AND notification_status = #{notificationStatus}
            </if>
            <if test="targetUserId != null">
                AND target_user_id = #{targetUserId}
            </if>
            ORDER BY created_at DESC
            </script>
            """)
    Page<GovernanceAlertNotificationRecord> queryNotificationRecords(
            Page<GovernanceAlertNotificationRecord> page,
            @Param("alertRecordId") Long alertRecordId,
            @Param("channelType") String channelType,
            @Param("notificationStatus") String notificationStatus,
            @Param("targetUserId") Long targetUserId);

    /**
     * 按告警记录ID查询通知记录列表
     */
    @Select("""
            SELECT * FROM release_governance_alert_notification_record
            WHERE alert_record_id = #{alertRecordId}
              AND deleted = 0
            ORDER BY created_at DESC
            """)
    Page<GovernanceAlertNotificationRecord> queryByAlertRecordId(
            Page<GovernanceAlertNotificationRecord> page,
            @Param("alertRecordId") Long alertRecordId);

    // ==================== S 阶段统计查询 ====================

    /**
     * 统计通知记录总数
     * Contract: v2-stage-s-contract.md Section 5.1
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM release_governance_alert_notification_record
            WHERE deleted = 0
            <if test="startTime != null">
                AND created_at &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND created_at &lt;= #{endTime}
            </if>
            </script>
            """)
    Long countTotalNotificationRecords(
            @Param("startTime") java.time.OffsetDateTime startTime,
            @Param("endTime") java.time.OffsetDateTime endTime);
}
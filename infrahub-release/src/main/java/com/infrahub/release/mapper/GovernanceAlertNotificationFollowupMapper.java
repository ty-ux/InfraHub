package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.release.entity.GovernanceAlertNotificationFollowup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 告警通知跟进记录 Mapper
 * Contract: v2-stage-r-contract.md Section 9.1
 * S 阶段统计查询: v2-stage-s-contract.md Section 5
 */
@Mapper
public interface GovernanceAlertNotificationFollowupMapper extends com.baomidou.mybatisplus.core.mapper.BaseMapper<GovernanceAlertNotificationFollowup> {

    /**
     * 获取下一个跟进编号序号
     */
    @Select("SELECT nextval('seq_followup_no')")
    Long getNextFollowupNoSequence();

    /**
     * 按条件分页查询跟进记录
     * Contract: v2-stage-r-contract.md Section 2.3
     */
    @Select("""
            <script>
            SELECT * FROM release_governance_alert_notification_followup
            WHERE deleted = 0
            <if test="notificationRecordId != null">
                AND notification_record_id = #{notificationRecordId}
            </if>
            <if test="followUpType != null and followUpType != ''">
                AND follow_up_type = #{followUpType}
            </if>
            <if test="receiptStatus != null and receiptStatus != ''">
                AND receipt_status = #{receiptStatus}
            </if>
            <if test="followUpStatus != null and followUpStatus != ''">
                AND follow_up_status = #{followUpStatus}
            </if>
            <if test="alertRecordId != null">
                AND alert_record_id = #{alertRecordId}
            </if>
            ORDER BY created_at DESC
            </script>
            """)
    Page<GovernanceAlertNotificationFollowup> queryFollowupRecords(
            Page<GovernanceAlertNotificationFollowup> page,
            @Param("notificationRecordId") Long notificationRecordId,
            @Param("followUpType") String followUpType,
            @Param("receiptStatus") String receiptStatus,
            @Param("followUpStatus") String followUpStatus,
            @Param("alertRecordId") Long alertRecordId);

    /**
     * 按通知记录ID查询跟进记录列表
     */
    @Select("""
            SELECT * FROM release_governance_alert_notification_followup
            WHERE notification_record_id = #{notificationRecordId}
              AND deleted = 0
            ORDER BY created_at DESC
            """)
    Page<GovernanceAlertNotificationFollowup> queryByNotificationId(
            Page<GovernanceAlertNotificationFollowup> page,
            @Param("notificationRecordId") Long notificationRecordId);

    // ==================== S 阶段统计查询 ====================

    /**
     * 统计跟进记录总数
     * Contract: v2-stage-s-contract.md Section 5.1
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM release_governance_alert_notification_followup
            WHERE deleted = 0
            <if test="startTime != null">
                AND created_at &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND created_at &lt;= #{endTime}
            </if>
            </script>
            """)
    Long countTotalFollowupRecords(
            @Param("startTime") java.time.OffsetDateTime startTime,
            @Param("endTime") java.time.OffsetDateTime endTime);

    /**
     * 按跟进状态统计数量
     * Contract: v2-stage-s-contract.md Section 5.2
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM release_governance_alert_notification_followup
            WHERE deleted = 0
              AND follow_up_status = #{followUpStatus}
            <if test="startTime != null">
                AND created_at &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND created_at &lt;= #{endTime}
            </if>
            </script>
            """)
    Long countByFollowUpStatus(
            @Param("followUpStatus") String followUpStatus,
            @Param("startTime") java.time.OffsetDateTime startTime,
            @Param("endTime") java.time.OffsetDateTime endTime);

    /**
     * 按回执状态统计数量
     * Contract: v2-stage-s-contract.md Section 5.3
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM release_governance_alert_notification_followup
            WHERE deleted = 0
              AND receipt_status = #{receiptStatus}
            <if test="startTime != null">
                AND created_at &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND created_at &lt;= #{endTime}
            </if>
            <if test="alertRecordId != null">
                AND alert_record_id = #{alertRecordId}
            </if>
            <if test="notificationRecordId != null">
                AND notification_record_id = #{notificationRecordId}
            </if>
            </script>
            """)
    Long countByReceiptStatus(
            @Param("receiptStatus") String receiptStatus,
            @Param("startTime") java.time.OffsetDateTime startTime,
            @Param("endTime") java.time.OffsetDateTime endTime,
            @Param("alertRecordId") Long alertRecordId,
            @Param("notificationRecordId") Long notificationRecordId);

    /**
     * 统计已跟进的通知数（有跟进记录的不重复通知数）
     * Contract: v2-stage-s-contract.md Section 5.1
     */
    @Select("""
            <script>
            SELECT COUNT(DISTINCT notification_record_id) FROM release_governance_alert_notification_followup
            WHERE deleted = 0
            <if test="startTime != null">
                AND created_at &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND created_at &lt;= #{endTime}
            </if>
            </script>
            """)
    Long countDistinctNotificationRecords(
            @Param("startTime") java.time.OffsetDateTime startTime,
            @Param("endTime") java.time.OffsetDateTime endTime);

    /**
     * 统计逾期跟进数（进行中且创建时间超过24小时）
     * Contract: v2-stage-s-contract.md Section 5.6
     * PostgreSQL: CURRENT_TIMESTAMP - INTERVAL '24 hours'
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM release_governance_alert_notification_followup
            WHERE deleted = 0
              AND follow_up_status = 'IN_PROGRESS'
              AND created_at &lt; CURRENT_TIMESTAMP - INTERVAL '24 hours'
            <if test="startTime != null">
                AND created_at &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND created_at &lt;= #{endTime}
            </if>
            </script>
            """)
    Long countOverdueFollowupRecords(
            @Param("startTime") java.time.OffsetDateTime startTime,
            @Param("endTime") java.time.OffsetDateTime endTime);

    /**
     * 按渠道类型统计回执状态分布
     * Contract: v2-stage-s-contract.md Section 3.2.1
     */
    @Select("""
            <script>
            SELECT
                nr.channel_type AS channelType,
                COUNT(*) AS totalCount,
                SUM(CASE WHEN f.receipt_status = 'CONFIRMED' THEN 1 ELSE 0 END) AS confirmedCount,
                SUM(CASE WHEN f.receipt_status = 'FAILED' THEN 1 ELSE 0 END) AS failedCount,
                SUM(CASE WHEN f.receipt_status = 'NO_RESPONSE' THEN 1 ELSE 0 END) AS noResponseCount
            FROM release_governance_alert_notification_followup f
            JOIN release_governance_alert_notification_record nr ON f.notification_record_id = nr.id
            WHERE f.deleted = 0 AND nr.deleted = 0
            <if test="startTime != null">
                AND f.created_at &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND f.created_at &lt;= #{endTime}
            </if>
            <if test="channelType != null and channelType != ''">
                AND nr.channel_type = #{channelType}
            </if>
            <if test="targetUserId != null">
                AND nr.target_user_id = #{targetUserId}
            </if>
            GROUP BY nr.channel_type
            </script>
            """)
    java.util.List<java.util.Map<String, Object>> countReceiptStatsByChannelType(
            @Param("startTime") java.time.OffsetDateTime startTime,
            @Param("endTime") java.time.OffsetDateTime endTime,
            @Param("channelType") String channelType,
            @Param("targetUserId") Long targetUserId);

    /**
     * 按目标用户统计回执状态分布（Top 10）
     * Contract: v2-stage-s-contract.md Section 3.2.2
     */
    @Select("""
            <script>
            SELECT
                nr.target_user_id AS targetUserId,
                nr.target_user_name AS targetUserName,
                COUNT(*) AS totalCount,
                SUM(CASE WHEN f.receipt_status = 'CONFIRMED' THEN 1 ELSE 0 END) AS confirmedCount,
                SUM(CASE WHEN f.receipt_status = 'FAILED' THEN 1 ELSE 0 END) AS failedCount,
                SUM(CASE WHEN f.receipt_status = 'NO_RESPONSE' THEN 1 ELSE 0 END) AS noResponseCount
            FROM release_governance_alert_notification_followup f
            JOIN release_governance_alert_notification_record nr ON f.notification_record_id = nr.id
            WHERE f.deleted = 0 AND nr.deleted = 0
            <if test="startTime != null">
                AND f.created_at &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND f.created_at &lt;= #{endTime}
            </if>
            <if test="channelType != null and channelType != ''">
                AND nr.channel_type = #{channelType}
            </if>
            <if test="targetUserId != null">
                AND nr.target_user_id = #{targetUserId}
            </if>
            GROUP BY nr.target_user_id, nr.target_user_name
            ORDER BY totalCount DESC
            LIMIT 10
            </script>
            """)
    java.util.List<java.util.Map<String, Object>> countReceiptStatsByTargetUser(
            @Param("startTime") java.time.OffsetDateTime startTime,
            @Param("endTime") java.time.OffsetDateTime endTime,
            @Param("channelType") String channelType,
            @Param("targetUserId") Long targetUserId);

    /**
     * 统计整体回执状态分布
     * Contract: v2-stage-s-contract.md Section 3.2.3
     */
    @Select("""
            <script>
            SELECT
                COUNT(*) AS totalCount,
                SUM(CASE WHEN f.receipt_status = 'CONFIRMED' THEN 1 ELSE 0 END) AS confirmedCount,
                SUM(CASE WHEN f.receipt_status = 'FAILED' THEN 1 ELSE 0 END) AS failedCount,
                SUM(CASE WHEN f.receipt_status = 'NO_RESPONSE' THEN 1 ELSE 0 END) AS noResponseCount
            FROM release_governance_alert_notification_followup f
            JOIN release_governance_alert_notification_record nr ON f.notification_record_id = nr.id
            WHERE f.deleted = 0 AND nr.deleted = 0
            <if test="startTime != null">
                AND f.created_at &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND f.created_at &lt;= #{endTime}
            </if>
            <if test="channelType != null and channelType != ''">
                AND nr.channel_type = #{channelType}
            </if>
            <if test="targetUserId != null">
                AND nr.target_user_id = #{targetUserId}
            </if>
            </script>
            """)
    java.util.Map<String, Object> countOverallReceiptStats(
            @Param("startTime") java.time.OffsetDateTime startTime,
            @Param("endTime") java.time.OffsetDateTime endTime,
            @Param("channelType") String channelType,
            @Param("targetUserId") Long targetUserId);

    /**
     * 按跟进状态分组统计
     * Contract: v2-stage-s-contract.md Section 3.3.1
     */
    @Select("""
            <script>
            SELECT
                follow_up_status AS followUpStatus,
                COUNT(*) AS count
            FROM release_governance_alert_notification_followup
            WHERE deleted = 0
            <if test="startTime != null">
                AND created_at &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND created_at &lt;= #{endTime}
            </if>
            <if test="alertRecordId != null">
                AND alert_record_id = #{alertRecordId}
            </if>
            <if test="notificationRecordId != null">
                AND notification_record_id = #{notificationRecordId}
            </if>
            GROUP BY follow_up_status
            </script>
            """)
    java.util.List<java.util.Map<String, Object>> countByFollowUpStatusGroup(
            @Param("startTime") java.time.OffsetDateTime startTime,
            @Param("endTime") java.time.OffsetDateTime endTime,
            @Param("alertRecordId") Long alertRecordId,
            @Param("notificationRecordId") Long notificationRecordId);

    /**
     * 按回执状态分组统计
     * Contract: v2-stage-s-contract.md Section 3.3.2
     */
    @Select("""
            <script>
            SELECT
                receipt_status AS receiptStatus,
                COUNT(*) AS count
            FROM release_governance_alert_notification_followup
            WHERE deleted = 0
            <if test="startTime != null">
                AND created_at &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND created_at &lt;= #{endTime}
            </if>
            <if test="alertRecordId != null">
                AND alert_record_id = #{alertRecordId}
            </if>
            <if test="notificationRecordId != null">
                AND notification_record_id = #{notificationRecordId}
            </if>
            GROUP BY receipt_status
            </script>
            """)
    java.util.List<java.util.Map<String, Object>> countByReceiptStatusGroup(
            @Param("startTime") java.time.OffsetDateTime startTime,
            @Param("endTime") java.time.OffsetDateTime endTime,
            @Param("alertRecordId") Long alertRecordId,
            @Param("notificationRecordId") Long notificationRecordId);

    /**
     * 按跟进类型分组统计
     * Contract: v2-stage-s-contract.md Section 3.3.3
     */
    @Select("""
            <script>
            SELECT
                follow_up_type AS followUpType,
                COUNT(*) AS count
            FROM release_governance_alert_notification_followup
            WHERE deleted = 0
            <if test="startTime != null">
                AND created_at &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND created_at &lt;= #{endTime}
            </if>
            <if test="alertRecordId != null">
                AND alert_record_id = #{alertRecordId}
            </if>
            <if test="notificationRecordId != null">
                AND notification_record_id = #{notificationRecordId}
            </if>
            GROUP BY follow_up_type
            </script>
            """)
    java.util.List<java.util.Map<String, Object>> countByFollowUpTypeGroup(
            @Param("startTime") java.time.OffsetDateTime startTime,
            @Param("endTime") java.time.OffsetDateTime endTime,
            @Param("alertRecordId") Long alertRecordId,
            @Param("notificationRecordId") Long notificationRecordId);

    /**
     * 计算平均首次跟进时间（分钟）
     * 查询每个通知的首次跟进时间与通知创建时间的差值平均值
     * Contract: v2-stage-s-contract.md Section 5.5
     * PostgreSQL: EXTRACT(EPOCH FROM (b - a)) / 60 for minutes
     */
    @Select("""
            <script>
            SELECT AVG(EXTRACT(EPOCH FROM (f.first_followup_at - nr.created_at)) / 60)
            FROM (
                SELECT
                    notification_record_id,
                    MIN(created_at) AS first_followup_at
                FROM release_governance_alert_notification_followup
                WHERE deleted = 0
                <if test="startTime != null">
                    AND created_at &gt;= #{startTime}
                </if>
                <if test="endTime != null">
                    AND created_at &lt;= #{endTime}
                </if>
                GROUP BY notification_record_id
            ) f
            JOIN release_governance_alert_notification_record nr ON f.notification_record_id = nr.id
            WHERE nr.deleted = 0
            </script>
            """)
    Double calculateAverageFirstFollowupMinutes(
            @Param("startTime") java.time.OffsetDateTime startTime,
            @Param("endTime") java.time.OffsetDateTime endTime);
}

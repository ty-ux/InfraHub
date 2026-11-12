package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.release.dto.GovernanceAlertFollowupByAlertItem;
import com.infrahub.release.dto.GovernanceAlertFollowupByHandlerItem;
import com.infrahub.release.dto.GovernanceAlertFollowupByNotificationItem;
import com.infrahub.release.dto.GovernanceAlertFollowupReviewDetailItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 告警通知跟进复盘分析 Mapper
 * Contract: v2-stage-s-contract.md
 *
 * S 阶段只读分析 API：
 * - GET /by-handler: 按负责人维度闭环分析
 * - GET /by-alert: 按告警维度闭环分析
 * - GET /by-notification: 按通知维度闭环分析
 * - GET /details: 复盘明细列表查询
 *
 * S 阶段不新增表，不新增字段，不新增枚举，不修改既有表。
 * 所有查询均为只读 SELECT。
 */
@Mapper
public interface GovernanceAlertFollowupReviewMapper {

    // ==================== 按负责人维度分析 ====================

    /**
     * 按负责人维度聚合统计
     * Contract: v2-stage-s-contract.md Section 3.4, 4.4
     *
     * 按 created_by (handler) 分组统计跟进记录
     */
    @Select("""
            <script>
            SELECT
                f.created_by AS handlerId,
                f.created_by_name AS handlerName,
                COUNT(*) AS totalFollowupCount,
                SUM(CASE WHEN f.follow_up_status = 'COMPLETED' THEN 1 ELSE 0 END) AS completedCount,
                SUM(CASE WHEN f.follow_up_status = 'IN_PROGRESS' THEN 1 ELSE 0 END) AS inProgressCount,
                SUM(CASE WHEN f.follow_up_status = 'CANCELLED' THEN 1 ELSE 0 END) AS cancelledCount,
                ROUND(
                    SUM(CASE WHEN f.follow_up_status = 'COMPLETED' THEN 1 ELSE 0 END) * 100.0 / NULLIF(COUNT(*), 0),
                    2
                ) AS completionRate,
                ROUND(
                    AVG(EXTRACT(EPOCH FROM (f.created_at - n.created_at)) / 60),
                    2
                ) AS averageFollowupMinutes,
                COUNT(DISTINCT CASE WHEN f.id = first_f.id THEN f.notification_record_id END) AS firstFollowupCount,
                ROUND(
                    AVG(CASE WHEN f.id = first_f.id THEN EXTRACT(EPOCH FROM (f.created_at - n.created_at)) / 60 END),
                    2
                ) AS averageFirstFollowupMinutes
            FROM release_governance_alert_notification_followup f
            LEFT JOIN release_governance_alert_notification_record n ON f.notification_record_id = n.id
            LEFT JOIN (
                SELECT notification_record_id, MIN(id) AS id
                FROM release_governance_alert_notification_followup
                WHERE deleted = 0
                GROUP BY notification_record_id
            ) first_f ON f.id = first_f.id
            WHERE f.deleted = 0
            <if test="startTime != null">
                AND f.created_at &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND f.created_at &lt;= #{endTime}
            </if>
            <if test="handlerId != null">
                AND f.created_by = #{handlerId}
            </if>
            GROUP BY f.created_by, f.created_by_name
            ORDER BY totalFollowupCount DESC
            </script>
            """)
    Page<Map<String, Object>> queryByHandler(
            Page<Map<String, Object>> page,
            @Param("startTime") OffsetDateTime startTime,
            @Param("endTime") OffsetDateTime endTime,
            @Param("handlerId") Long handlerId);

    // ==================== 按告警维度分析 ====================

    /**
     * 按告警维度聚合统计
     * Contract: v2-stage-s-contract.md Section 3.5, 4.5
     *
     * 注意：alert_title 存在于 notification_record 表（Q 阶段冗余），
     * alert_record 表只有 rule_name/reason，没有 alert_title。
     * 此处取每个告警下第一条通知的 alert_title 作为代表。
     * alert_level / alert_status 来自 alert_record 表。
     */
    @Select("""
            <script>
            SELECT
                ar.id AS alertRecordId,
                ar.alert_no AS alertNo,
                first_n.alert_title AS alertTitle,
                ar.alert_level AS alertLevel,
                ar.alert_status AS alertStatus,
                COUNT(DISTINCT n.id) AS notificationCount,
                COUNT(f.id) AS followupCount,
                COUNT(DISTINCT f.created_by) AS uniqueHandlerCount,
                SUM(CASE WHEN f.receipt_status = 'CONFIRMED' THEN 1 ELSE 0 END) AS confirmedReceiptCount,
                SUM(CASE WHEN f.receipt_status = 'PENDING' THEN 1 ELSE 0 END) AS pendingReceiptCount,
                ROUND(
                    SUM(CASE WHEN f.receipt_status = 'CONFIRMED' THEN 1 ELSE 0 END) * 100.0 / NULLIF(COUNT(DISTINCT n.id), 0),
                    2
                ) AS closedLoopRate,
                MAX(f.created_at) AS latestFollowupAt,
                ar.created_at AS createdAt
            FROM release_governance_alert_record ar
            LEFT JOIN release_governance_alert_notification_record n ON ar.id = n.alert_record_id AND n.deleted = 0
            LEFT JOIN (
                SELECT DISTINCT ON (alert_record_id) id, alert_record_id, alert_title
                FROM release_governance_alert_notification_record
                WHERE deleted = 0
                ORDER BY alert_record_id, id
            ) first_n ON ar.id = first_n.alert_record_id
            LEFT JOIN release_governance_alert_notification_followup f ON n.id = f.notification_record_id AND f.deleted = 0
            WHERE 1=1
            <if test="startTime != null">
                AND (f.created_at IS NULL OR f.created_at &gt;= #{startTime})
            </if>
            <if test="endTime != null">
                AND (f.created_at IS NULL OR f.created_at &lt;= #{endTime})
            </if>
            <if test="alertRecordId != null">
                AND ar.id = #{alertRecordId}
            </if>
            <if test="alertLevel != null and alertLevel != ''">
                AND ar.alert_level = #{alertLevel}
            </if>
            <if test="alertStatus != null and alertStatus != ''">
                AND ar.alert_status = #{alertStatus}
            </if>
            GROUP BY ar.id, ar.alert_no, first_n.alert_title, ar.alert_level, ar.alert_status, ar.created_at
            HAVING COUNT(f.id) &gt; 0
            ORDER BY followupCount DESC
            </script>
            """)
    Page<Map<String, Object>> queryByAlert(
            Page<Map<String, Object>> page,
            @Param("startTime") OffsetDateTime startTime,
            @Param("endTime") OffsetDateTime endTime,
            @Param("alertRecordId") Long alertRecordId,
            @Param("alertLevel") String alertLevel,
            @Param("alertStatus") String alertStatus);

    // ==================== 按通知维度分析 ====================

    /**
     * 按通知维度聚合统计
     * Contract: v2-stage-s-contract.md Section 3.6, 4.6
     *
     * 注意：alert_title 存在于 notification_record 表（Q 阶段冗余），
     * alert_record 表没有 alert_title 列。
     */
    @Select("""
            <script>
            SELECT
                n.id AS notificationRecordId,
                n.notification_no AS notificationNo,
                n.alert_record_id AS alertRecordId,
                n.alert_no AS alertNo,
                n.alert_title AS alertTitle,
                n.channel_type AS channelType,
                n.notification_status AS notificationStatus,
                n.target_user_id AS targetUserId,
                n.target_user_name AS targetUserName,
                COUNT(f.id) AS followupCount,
                (SELECT f2.follow_up_status FROM release_governance_alert_notification_followup f2
                 WHERE f2.notification_record_id = n.id AND f2.deleted = 0
                 ORDER BY f2.created_at DESC LIMIT 1) AS latestFollowupStatus,
                (SELECT f2.receipt_status FROM release_governance_alert_notification_followup f2
                 WHERE f2.notification_record_id = n.id AND f2.deleted = 0
                 ORDER BY f2.created_at DESC LIMIT 1) AS latestReceiptStatus,
                (SELECT MIN(f3.created_at) FROM release_governance_alert_notification_followup f3
                 WHERE f3.notification_record_id = n.id AND f3.deleted = 0) AS firstFollowupAt,
                (SELECT MAX(f4.created_at) FROM release_governance_alert_notification_followup f4
                 WHERE f4.notification_record_id = n.id AND f4.deleted = 0) AS latestFollowupAt,
                n.created_at AS createdAt
            FROM release_governance_alert_notification_record n
            LEFT JOIN release_governance_alert_notification_followup f ON n.id = f.notification_record_id AND f.deleted = 0
            WHERE n.deleted = 0
            <if test="startTime != null">
                AND n.created_at &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND n.created_at &lt;= #{endTime}
            </if>
            <if test="notificationRecordId != null">
                AND n.id = #{notificationRecordId}
            </if>
            <if test="alertRecordId != null">
                AND n.alert_record_id = #{alertRecordId}
            </if>
            <if test="targetUserId != null">
                AND n.target_user_id = #{targetUserId}
            </if>
            <if test="channelType != null and channelType != ''">
                AND n.channel_type = #{channelType}
            </if>
            <if test="followUpStatus != null and followUpStatus != ''">
                AND EXISTS (
                    SELECT 1 FROM release_governance_alert_notification_followup f5
                    WHERE f5.notification_record_id = n.id AND f5.deleted = 0
                    AND f5.follow_up_status = #{followUpStatus}
                )
            </if>
            <if test="receiptStatus != null and receiptStatus != ''">
                AND EXISTS (
                    SELECT 1 FROM release_governance_alert_notification_followup f6
                    WHERE f6.notification_record_id = n.id AND f6.deleted = 0
                    AND f6.receipt_status = #{receiptStatus}
                )
            </if>
            GROUP BY n.id, n.notification_no, n.alert_record_id, n.alert_no, n.alert_title,
                     n.channel_type, n.notification_status, n.target_user_id, n.target_user_name, n.created_at
            ORDER BY n.created_at DESC
            </script>
            """)
    Page<Map<String, Object>> queryByNotification(
            Page<Map<String, Object>> page,
            @Param("startTime") OffsetDateTime startTime,
            @Param("endTime") OffsetDateTime endTime,
            @Param("notificationRecordId") Long notificationRecordId,
            @Param("alertRecordId") Long alertRecordId,
            @Param("targetUserId") Long targetUserId,
            @Param("channelType") String channelType,
            @Param("followUpStatus") String followUpStatus,
            @Param("receiptStatus") String receiptStatus);

    // ==================== 复盘明细列表查询 ====================

    /**
     * 复盘明细列表查询
     * Contract: v2-stage-s-contract.md Section 3.7, 4.7
     *
     * 注意：alert_title 来自 notification_record 表（Q 阶段冗余），
     * alert_level / alert_status 来自 alert_record 表。
     */
    @Select("""
            <script>
            SELECT
                f.id AS followupId,
                f.follow_up_no AS followUpNo,
                f.notification_record_id AS notificationRecordId,
                f.notification_no AS notificationNo,
                f.alert_record_id AS alertRecordId,
                f.alert_no AS alertNo,
                n.alert_title AS alertTitle,
                ar.alert_level AS alertLevel,
                ar.alert_status AS alertStatus,
                n.channel_type AS channelType,
                n.target_user_id AS targetUserId,
                n.target_user_name AS targetUserName,
                f.follow_up_type AS followUpType,
                f.follow_up_content AS followUpContent,
                f.follow_up_result AS followUpResult,
                f.follow_up_status AS followUpStatus,
                f.receipt_status AS receiptStatus,
                f.receipt_note AS receiptNote,
                f.created_by AS createdBy,
                f.created_by_name AS createdByName,
                f.created_at AS createdAt,
                f.confirmed_by AS confirmedBy,
                f.confirmed_by_name AS confirmedByName,
                f.confirmed_at AS confirmedAt,
                CASE WHEN f.id = first_f.id THEN true ELSE false END AS firstFollowupForNotification,
                EXTRACT(EPOCH FROM (f.created_at - n.created_at)) / 60 AS minutesSinceNotification
            FROM release_governance_alert_notification_followup f
            LEFT JOIN release_governance_alert_notification_record n ON f.notification_record_id = n.id
            LEFT JOIN release_governance_alert_record ar ON f.alert_record_id = ar.id
            LEFT JOIN (
                SELECT notification_record_id, MIN(id) AS id
                FROM release_governance_alert_notification_followup
                WHERE deleted = 0
                GROUP BY notification_record_id
            ) first_f ON f.notification_record_id = first_f.notification_record_id AND f.id = first_f.id
            WHERE f.deleted = 0
            <if test="startTime != null">
                AND f.created_at &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND f.created_at &lt;= #{endTime}
            </if>
            <if test="alertRecordId != null">
                AND f.alert_record_id = #{alertRecordId}
            </if>
            <if test="notificationRecordId != null">
                AND f.notification_record_id = #{notificationRecordId}
            </if>
            <if test="targetUserId != null">
                AND n.target_user_id = #{targetUserId}
            </if>
            <if test="createdBy != null">
                AND f.created_by = #{createdBy}
            </if>
            <if test="followUpType != null and followUpType != ''">
                AND f.follow_up_type = #{followUpType}
            </if>
            <if test="followUpStatus != null and followUpStatus != ''">
                AND f.follow_up_status = #{followUpStatus}
            </if>
            <if test="receiptStatus != null and receiptStatus != ''">
                AND f.receipt_status = #{receiptStatus}
            </if>
            ORDER BY f.created_at DESC
            </script>
            """)
    Page<Map<String, Object>> queryDetails(
            Page<Map<String, Object>> page,
            @Param("startTime") OffsetDateTime startTime,
            @Param("endTime") OffsetDateTime endTime,
            @Param("alertRecordId") Long alertRecordId,
            @Param("notificationRecordId") Long notificationRecordId,
            @Param("targetUserId") Long targetUserId,
            @Param("createdBy") Long createdBy,
            @Param("followUpType") String followUpType,
            @Param("followUpStatus") String followUpStatus,
            @Param("receiptStatus") String receiptStatus);
}

package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.release.entity.GovernanceAlertRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 告警记录 Mapper
 * Contract: v2-stage-m-contract.md
 */
@Mapper
public interface GovernanceAlertRecordMapper extends com.baomidou.mybatisplus.core.mapper.BaseMapper<GovernanceAlertRecord> {

    /**
     * 分页查询告警记录
     */
    @Select("""
            <script>
            SELECT * FROM release_governance_alert_record
            WHERE 1=1
            <if test="metricType != null and metricType != ''">
                AND metric_type = #{metricType}
            </if>
            <if test="alertLevel != null and alertLevel != ''">
                AND alert_level = #{alertLevel}
            </if>
            <if test="alertStatus != null and alertStatus != ''">
                AND alert_status = #{alertStatus}
            </if>
            <if test="envId != null">
                AND env_id = #{envId}
            </if>
            <if test="resourcePoolId != null">
                AND resource_pool_id = #{resourcePoolId}
            </if>
            <if test="serviceId != null">
                AND service_id = #{serviceId}
            </if>
            <if test="startTime != null">
                AND created_at &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND created_at &lt;= #{endTime}
            </if>
            ORDER BY created_at DESC
            </script>
            """)
    Page<GovernanceAlertRecord> queryAlertRecords(Page<GovernanceAlertRecord> page,
                                                   @Param("metricType") String metricType,
                                                   @Param("alertLevel") String alertLevel,
                                                   @Param("alertStatus") String alertStatus,
                                                   @Param("envId") Long envId,
                                                   @Param("resourcePoolId") Long resourcePoolId,
                                                   @Param("serviceId") Long serviceId,
                                                   @Param("startTime") OffsetDateTime startTime,
                                                   @Param("endTime") OffsetDateTime endTime);

    /**
     * 获取下一个告警编号序号
     */
    @Select("SELECT nextval('seq_alert_record_no')")
    Long getNextAlertNoSequence();

    /**
     * 按分派人ID分页查询告警记录
     * Contract: v2-stage-o-contract.md Section 2.4
     */
    @Select("""
            SELECT * FROM release_governance_alert_record
            WHERE assignee_id = #{assigneeId}
            ORDER BY assigned_at DESC
            """)
    Page<GovernanceAlertRecord> queryByAssigneeId(Page<GovernanceAlertRecord> page,
                                                   @Param("assigneeId") Long assigneeId);

    /**
     * 按分派人ID分页查询告警记录（带筛选条件）
     * Contract: v2-stage-p-contract.md Section 4.3
     */
    @Select("""
            <script>
            SELECT * FROM release_governance_alert_record
            WHERE assignee_id = #{assigneeId}
            <if test="status != null and status != ''">
                AND alert_status = #{status}
            </if>
            <if test="level != null and level != ''">
                AND alert_level = #{level}
            </if>
            <if test="overdueOnly != null and overdueOnly">
                AND assignment_due_at &lt; NOW()
                AND alert_status != 'RESOLVED'
            </if>
            ORDER BY assigned_at DESC
            </script>
            """)
    Page<GovernanceAlertRecord> queryByAssigneeIdWithFilters(Page<GovernanceAlertRecord> page,
                                                              @Param("assigneeId") Long assigneeId,
                                                              @Param("status") String status,
                                                              @Param("level") String level,
                                                              @Param("overdueOnly") Boolean overdueOnly);

    /**
     * 查询已升级告警列表
     * Contract: v2-stage-o-contract.md Section 2.4
     */
    @Select("""
            SELECT * FROM release_governance_alert_record
            WHERE escalated = true
            ORDER BY escalated_at DESC
            """)
    Page<GovernanceAlertRecord> queryEscalatedAlerts(Page<GovernanceAlertRecord> page);

    /**
     * 查询已升级告警列表（带筛选条件）
     * Contract: v2-stage-p-contract.md Section 4.2
     */
    @Select("""
            <script>
            SELECT * FROM release_governance_alert_record
            WHERE escalated = true
            <if test="escalationLevel != null and escalationLevel != ''">
                AND escalation_level = #{escalationLevel}
            </if>
            <if test="status != null and status != ''">
                AND alert_status = #{status}
            </if>
            ORDER BY escalated_at DESC
            </script>
            """)
    Page<GovernanceAlertRecord> queryEscalatedAlertsWithFilters(Page<GovernanceAlertRecord> page,
                                                                @Param("escalationLevel") String escalationLevel,
                                                                @Param("status") String status);

    /**
     * 查询逾期告警列表
     * Contract: v2-stage-p-contract.md Section 4.1
     * 逾期定义：assignment_due_at < now 且 alert_status != 'RESOLVED'
     */
    @Select("""
            <script>
            SELECT * FROM release_governance_alert_record
            WHERE assignment_due_at &lt; NOW()
              AND alert_status != 'RESOLVED'
            <if test="assigneeId != null">
                AND assignee_id = #{assigneeId}
            </if>
            <if test="level != null and level != ''">
                AND alert_level = #{level}
            </if>
            <if test="escalated != null">
                AND escalated = #{escalated}
            </if>
            ORDER BY assignment_due_at ASC
            </script>
            """)
    Page<GovernanceAlertRecord> queryOverdueAlerts(Page<GovernanceAlertRecord> page,
                                                    @Param("assigneeId") Long assigneeId,
                                                    @Param("level") String level,
                                                    @Param("escalated") Boolean escalated);

    /**
     * 统计指定状态的告警数量
     */
    @Select("SELECT COUNT(*) FROM release_governance_alert_record WHERE alert_status = #{status}")
    Long countByStatus(@Param("status") String status);

    /**
     * 统计分派给指定用户的告警数量
     */
    @Select("SELECT COUNT(*) FROM release_governance_alert_record WHERE assignee_id = #{assigneeId}")
    Long countByAssigneeId(@Param("assigneeId") Long assigneeId);

    /**
     * 统计逾期告警数量
     * 逾期定义：assignment_due_at < now 且 alert_status != 'RESOLVED'
     */
    @Select("SELECT COUNT(*) FROM release_governance_alert_record WHERE assignment_due_at < NOW() AND alert_status != 'RESOLVED'")
    Long countOverdue();

    /**
     * 统计已升级告警数量
     */
    @Select("SELECT COUNT(*) FROM release_governance_alert_record WHERE escalated = true")
    Long countEscalated();

    /**
     * 统计指定等级的告警数量
     */
    @Select("SELECT COUNT(*) FROM release_governance_alert_record WHERE alert_level = #{level}")
    Long countByLevel(@Param("level") String level);
}
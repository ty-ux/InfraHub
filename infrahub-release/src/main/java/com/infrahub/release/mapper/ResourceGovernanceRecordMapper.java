package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.release.entity.ResourceGovernanceRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ResourceGovernanceRecordMapper extends com.baomidou.mybatisplus.core.mapper.BaseMapper<ResourceGovernanceRecord> {

    @Select("""
            <script>
            SELECT * FROM release_resource_governance_record
            WHERE 1=1
            <if test="resourcePoolId != null">
                AND resource_pool_id = #{resourcePoolId}
            </if>
            <if test="hostId != null">
                AND host_id = #{hostId}
            </if>
            <if test="runtimeInstanceId != null">
                AND runtime_instance_id = #{runtimeInstanceId}
            </if>
            <if test="releaseOrderId != null">
                AND release_order_id = #{releaseOrderId}
            </if>
            <if test="rollbackOrderId != null">
                AND rollback_order_id = #{rollbackOrderId}
            </if>
            <if test="envId != null">
                AND env_id = #{envId}
            </if>
            <if test="serviceId != null">
                AND service_id = #{serviceId}
            </if>
            <if test="eventType != null and eventType != ''">
                AND event_type = #{eventType}
            </if>
            <if test="resultStatus != null and resultStatus != ''">
                AND result_status = #{resultStatus}
            </if>
            <if test="subjectType != null and subjectType != ''">
                AND subject_type = #{subjectType}
            </if>
            <if test="subjectId != null">
                AND subject_id = #{subjectId}
            </if>
            ORDER BY created_at DESC
            </script>
            """)
    Page<ResourceGovernanceRecord> queryRecords(Page<ResourceGovernanceRecord> page,
                                                  @Param("resourcePoolId") Long resourcePoolId,
                                                  @Param("hostId") Long hostId,
                                                  @Param("runtimeInstanceId") Long runtimeInstanceId,
                                                  @Param("releaseOrderId") Long releaseOrderId,
                                                  @Param("rollbackOrderId") Long rollbackOrderId,
                                                  @Param("envId") Long envId,
                                                  @Param("serviceId") Long serviceId,
                                                  @Param("eventType") String eventType,
                                                  @Param("resultStatus") String resultStatus,
                                                  @Param("subjectType") String subjectType,
                                                  @Param("subjectId") Long subjectId);

    /**
     * 时间线查询 - 支持时间范围和关联字段筛选
     * Contract: TimelineQueryParams
     * 支持 ID 和 No 双重筛选: actionPlanId/actionPlanNo, actionExecutionId/actionExecutionNo, suggestionId/suggestionNo
     */
    @Select("""
            <script>
            SELECT r.* FROM release_resource_governance_record r
            WHERE 1=1
            <if test="subjectType != null and subjectType != ''">
                AND r.subject_type = #{subjectType}
            </if>
            <if test="subjectId != null">
                AND r.subject_id = #{subjectId}
            </if>
            <if test="actionPlanId != null">
                AND EXISTS (SELECT 1 FROM release_resource_governance_action_plan p WHERE p.id = #{actionPlanId} AND p.plan_no = r.action_plan_no)
            </if>
            <if test="actionPlanId == null and actionPlanNo != null and actionPlanNo != ''">
                AND r.action_plan_no = #{actionPlanNo}
            </if>
            <if test="actionExecutionId != null">
                AND EXISTS (SELECT 1 FROM release_resource_governance_action_execution e WHERE e.id = #{actionExecutionId} AND e.execution_no = r.action_execution_no)
            </if>
            <if test="actionExecutionId == null and actionExecutionNo != null and actionExecutionNo != ''">
                AND r.action_execution_no = #{actionExecutionNo}
            </if>
            <if test="suggestionId != null">
                AND EXISTS (SELECT 1 FROM release_resource_governance_suggestion s WHERE s.id = #{suggestionId} AND s.suggestion_no = r.suggestion_no)
            </if>
            <if test="suggestionId == null and suggestionNo != null and suggestionNo != ''">
                AND r.suggestion_no = #{suggestionNo}
            </if>
            <if test="eventType != null and eventType != ''">
                AND r.event_type = #{eventType}
            </if>
            <if test="resultStatus != null and resultStatus != ''">
                AND r.result_status = #{resultStatus}
            </if>
            <if test="startTime != null">
                AND r.created_at &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND r.created_at &lt;= #{endTime}
            </if>
            ORDER BY r.created_at ASC
            </script>
            """)
    Page<ResourceGovernanceRecord> queryTimeline(Page<ResourceGovernanceRecord> page,
                                                   @Param("subjectType") String subjectType,
                                                   @Param("subjectId") Long subjectId,
                                                   @Param("actionPlanId") Long actionPlanId,
                                                   @Param("actionPlanNo") String actionPlanNo,
                                                   @Param("actionExecutionId") Long actionExecutionId,
                                                   @Param("actionExecutionNo") String actionExecutionNo,
                                                   @Param("suggestionId") Long suggestionId,
                                                   @Param("suggestionNo") String suggestionNo,
                                                   @Param("eventType") String eventType,
                                                   @Param("resultStatus") String resultStatus,
                                                   @Param("startTime") java.time.OffsetDateTime startTime,
                                                   @Param("endTime") java.time.OffsetDateTime endTime);

    /**
     * 按动作计划编号查询时间线
     */
    @Select("""
            SELECT * FROM release_resource_governance_record
            WHERE action_plan_no = #{actionPlanNo}
            ORDER BY created_at ASC
            """)
    java.util.List<ResourceGovernanceRecord> queryTimelineByActionPlanNo(@Param("actionPlanNo") String actionPlanNo);

    /**
     * 按执行记录编号查询时间线
     */
    @Select("""
            SELECT * FROM release_resource_governance_record
            WHERE action_execution_no = #{actionExecutionNo}
            ORDER BY created_at ASC
            """)
    java.util.List<ResourceGovernanceRecord> queryTimelineByActionExecutionNo(@Param("actionExecutionNo") String actionExecutionNo);

    /**
     * 汇总统计 - 按资源池聚合
     */
    @Select("""
            <script>
            SELECT
                resource_pool_id as resourcePoolId,
                COUNT(*) as totalExecutions,
                SUM(CASE WHEN result_status = 'SUCCESS' THEN 1 ELSE 0 END) as successExecutions,
                SUM(CASE WHEN result_status = 'FAILED' THEN 1 ELSE 0 END) as failedExecutions,
                SUM(CASE WHEN result_status = 'CANCELLED' THEN 1 ELSE 0 END) as cancelledExecutions,
                MAX(created_at) as lastExecutionAt
            FROM release_resource_governance_record
            WHERE 1=1
            <if test="resourcePoolId != null">
                AND resource_pool_id = #{resourcePoolId}
            </if>
            <if test="eventType != null and eventType != ''">
                AND event_type = #{eventType}
            </if>
            <if test="resultStatus != null and resultStatus != ''">
                AND result_status = #{resultStatus}
            </if>
            <if test="subjectType != null and subjectType != ''">
                AND subject_type = #{subjectType}
            </if>
            GROUP BY resource_pool_id
            </script>
            """)
    java.util.List<java.util.Map<String, Object>> querySummaryByResourcePool(
            @Param("resourcePoolId") Long resourcePoolId,
            @Param("eventType") String eventType,
            @Param("resultStatus") String resultStatus,
            @Param("subjectType") String subjectType);

    /**
     * 按影响类型统计
     */
    @Select("""
            <script>
            SELECT
                event_type as eventType,
                COUNT(*) as count
            FROM release_resource_governance_record
            WHERE 1=1
            <if test="resourcePoolId != null">
                AND resource_pool_id = #{resourcePoolId}
            </if>
            <if test="subjectType != null and subjectType != ''">
                AND subject_type = #{subjectType}
            </if>
            GROUP BY event_type
            </script>
            """)
    java.util.List<java.util.Map<String, Object>> queryImpactByType(
            @Param("resourcePoolId") Long resourcePoolId,
            @Param("subjectType") String subjectType);
}

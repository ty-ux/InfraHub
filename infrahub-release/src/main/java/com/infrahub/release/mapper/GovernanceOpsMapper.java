package com.infrahub.release.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 治理运营聚合查询 Mapper
 * Contract: v2-stage-l-contract.md
 */
@Mapper
public interface GovernanceOpsMapper {

    /**
     * 统计风险总数（从建议单聚合）
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM release_resource_governance_suggestion
            WHERE 1=1
            <if test="envId != null">
                AND env_id = #{envId}
            </if>
            <if test="resourcePoolId != null">
                AND resource_pool_id = #{resourcePoolId}
            </if>
            <if test="startTime != null">
                AND created_at &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND created_at &lt;= #{endTime}
            </if>
            </script>
            """)
    Long countTotalRisks(@Param("envId") Long envId,
                         @Param("resourcePoolId") Long resourcePoolId,
                         @Param("startTime") OffsetDateTime startTime,
                         @Param("endTime") OffsetDateTime endTime);

    /**
     * 按风险等级统计数量
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM release_resource_governance_suggestion
            WHERE risk_level = #{riskLevel}
            <if test="envId != null">
                AND env_id = #{envId}
            </if>
            <if test="resourcePoolId != null">
                AND resource_pool_id = #{resourcePoolId}
            </if>
            <if test="startTime != null">
                AND created_at &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND created_at &lt;= #{endTime}
            </if>
            </script>
            """)
    Long countRisksByLevel(@Param("envId") Long envId,
                           @Param("resourcePoolId") Long resourcePoolId,
                           @Param("riskLevel") String riskLevel,
                           @Param("startTime") OffsetDateTime startTime,
                           @Param("endTime") OffsetDateTime endTime);

    /**
     * 统计待处理建议单数（状态为 OPEN）
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM release_resource_governance_suggestion
            WHERE status = 'OPEN'
            <if test="envId != null">
                AND env_id = #{envId}
            </if>
            <if test="resourcePoolId != null">
                AND resource_pool_id = #{resourcePoolId}
            </if>
            <if test="startTime != null">
                AND created_at &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND created_at &lt;= #{endTime}
            </if>
            </script>
            """)
    Long countOpenSuggestions(@Param("envId") Long envId,
                              @Param("resourcePoolId") Long resourcePoolId,
                              @Param("startTime") OffsetDateTime startTime,
                              @Param("endTime") OffsetDateTime endTime);

    /**
     * 统计已确认计划数（状态为 CONFIRMED）
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM release_resource_governance_action_plan
            WHERE status = 'CONFIRMED'
            <if test="envId != null">
                AND env_id = #{envId}
            </if>
            <if test="resourcePoolId != null">
                AND resource_pool_id = #{resourcePoolId}
            </if>
            <if test="startTime != null">
                AND created_at &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND created_at &lt;= #{endTime}
            </if>
            </script>
            """)
    Long countConfirmedPlans(@Param("envId") Long envId,
                             @Param("resourcePoolId") Long resourcePoolId,
                             @Param("startTime") OffsetDateTime startTime,
                             @Param("endTime") OffsetDateTime endTime);

    /**
     * 统计执行中记录数（状态为 RUNNING）
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM release_resource_governance_action_execution e
            WHERE e.status = 'RUNNING'
            <if test="envId != null">
                AND EXISTS (SELECT 1 FROM release_resource_governance_action_plan p
                            WHERE p.id = e.plan_id AND p.env_id = #{envId})
            </if>
            <if test="resourcePoolId != null">
                AND EXISTS (SELECT 1 FROM release_resource_governance_action_plan p
                            WHERE p.id = e.plan_id AND p.resource_pool_id = #{resourcePoolId})
            </if>
            <if test="startTime != null">
                AND e.created_at &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND e.created_at &lt;= #{endTime}
            </if>
            </script>
            """)
    Long countRunningExecutions(@Param("envId") Long envId,
                                @Param("resourcePoolId") Long resourcePoolId,
                                @Param("startTime") OffsetDateTime startTime,
                                @Param("endTime") OffsetDateTime endTime);

    /**
     * 按执行状态统计数量
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM release_resource_governance_action_execution e
            WHERE e.status = #{status}
            <if test="envId != null">
                AND EXISTS (SELECT 1 FROM release_resource_governance_action_plan p
                            WHERE p.id = e.plan_id AND p.env_id = #{envId})
            </if>
            <if test="resourcePoolId != null">
                AND EXISTS (SELECT 1 FROM release_resource_governance_action_plan p
                            WHERE p.id = e.plan_id AND p.resource_pool_id = #{resourcePoolId})
            </if>
            <if test="startTime != null">
                AND e.created_at &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND e.created_at &lt;= #{endTime}
            </if>
            </script>
            """)
    Long countExecutionsByStatus(@Param("envId") Long envId,
                                 @Param("resourcePoolId") Long resourcePoolId,
                                 @Param("status") String status,
                                 @Param("startTime") OffsetDateTime startTime,
                                 @Param("endTime") OffsetDateTime endTime);

    /**
     * 统计已记录影响数（eventType = RESOURCE_GOVERNANCE_ACTION_IMPACT_RECORDED）
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM release_resource_governance_record
            WHERE event_type = 'RESOURCE_GOVERNANCE_ACTION_IMPACT_RECORDED'
            <if test="envId != null">
                AND env_id = #{envId}
            </if>
            <if test="resourcePoolId != null">
                AND resource_pool_id = #{resourcePoolId}
            </if>
            <if test="startTime != null">
                AND created_at &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND created_at &lt;= #{endTime}
            </if>
            </script>
            """)
    Long countImpactRecorded(@Param("envId") Long envId,
                             @Param("resourcePoolId") Long resourcePoolId,
                             @Param("startTime") OffsetDateTime startTime,
                             @Param("endTime") OffsetDateTime endTime);

    /**
     * 计算平均闭环耗时（毫秒）
     * 从建议单创建到执行完成的总耗时
     */
    @Select("""
            <script>
            SELECT AVG(
                EXTRACT(EPOCH FROM (e.finished_at - s.created_at)) * 1000
            ) as avg_duration_ms
            FROM release_resource_governance_suggestion s
            JOIN release_resource_governance_action_plan p ON p.suggestion_id = s.id
            JOIN release_resource_governance_action_execution e ON e.plan_id = p.id
            WHERE e.status IN ('SUCCESS', 'FAILED')
            AND e.finished_at IS NOT NULL
            <if test="envId != null">
                AND s.env_id = #{envId}
            </if>
            <if test="resourcePoolId != null">
                AND s.resource_pool_id = #{resourcePoolId}
            </if>
            <if test="startTime != null">
                AND s.created_at &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND s.created_at &lt;= #{endTime}
            </if>
            </script>
            """)
    Double calculateAvgCloseDurationMs(@Param("envId") Long envId,
                                       @Param("resourcePoolId") Long resourcePoolId,
                                       @Param("startTime") OffsetDateTime startTime,
                                       @Param("endTime") OffsetDateTime endTime);

    /**
     * 按天聚合趋势数据
     */
    @Select("""
            <script>
            WITH date_series AS (
                SELECT generate_series(
                    DATE_TRUNC('DAY', #{startTime}::timestamp),
                    DATE_TRUNC('DAY', #{endTime}::timestamp),
                    INTERVAL '1 day'
                )::date as day
            ),
            suggestions AS (
                SELECT DATE_TRUNC('DAY', created_at)::date as day, COUNT(*) as cnt
                FROM release_resource_governance_suggestion
                WHERE 1=1
                <if test="envId != null">
                    AND env_id = #{envId}
                </if>
                <if test="resourcePoolId != null">
                    AND resource_pool_id = #{resourcePoolId}
                </if>
                AND created_at BETWEEN #{startTime} AND #{endTime}
                GROUP BY DATE_TRUNC('DAY', created_at)::date
            ),
            plans AS (
                SELECT DATE_TRUNC('DAY', created_at)::date as day, COUNT(*) as cnt
                FROM release_resource_governance_action_plan
                WHERE 1=1
                <if test="envId != null">
                    AND env_id = #{envId}
                </if>
                <if test="resourcePoolId != null">
                    AND resource_pool_id = #{resourcePoolId}
                </if>
                AND created_at BETWEEN #{startTime} AND #{endTime}
                GROUP BY DATE_TRUNC('DAY', created_at)::date
            ),
            executions AS (
                SELECT DATE_TRUNC('DAY', e.created_at)::date as day,
                       COUNT(*) as total,
                       SUM(CASE WHEN e.status = 'SUCCESS' THEN 1 ELSE 0 END) as success,
                       SUM(CASE WHEN e.status = 'FAILED' THEN 1 ELSE 0 END) as failed
                FROM release_resource_governance_action_execution e
                WHERE 1=1
                <if test="envId != null">
                    AND EXISTS (SELECT 1 FROM release_resource_governance_action_plan p
                                WHERE p.id = e.plan_id AND p.env_id = #{envId})
                </if>
                <if test="resourcePoolId != null">
                    AND EXISTS (SELECT 1 FROM release_resource_governance_action_plan p
                                WHERE p.id = e.plan_id AND p.resource_pool_id = #{resourcePoolId})
                </if>
                AND e.created_at BETWEEN #{startTime} AND #{endTime}
                GROUP BY DATE_TRUNC('DAY', e.created_at)::date
            ),
            impacts AS (
                SELECT DATE_TRUNC('DAY', created_at)::date as day, COUNT(*) as cnt
                FROM release_resource_governance_record
                WHERE event_type = 'RESOURCE_GOVERNANCE_ACTION_IMPACT_RECORDED'
                <if test="envId != null">
                    AND env_id = #{envId}
                </if>
                <if test="resourcePoolId != null">
                    AND resource_pool_id = #{resourcePoolId}
                </if>
                AND created_at BETWEEN #{startTime} AND #{endTime}
                GROUP BY DATE_TRUNC('DAY', created_at)::date
            )
            SELECT
                TO_CHAR(d.day, 'YYYY-MM-DD') as timeBucket,
                COALESCE(s.cnt, 0) as riskCount,
                COALESCE(s.cnt, 0) as suggestionCount,
                COALESCE(p.cnt, 0) as actionPlanCount,
                COALESCE(e.total, 0) as executionCount,
                COALESCE(i.cnt, 0) as impactCount,
                COALESCE(e.success, 0) as successCount,
                COALESCE(e.failed, 0) as failedCount
            FROM date_series d
            LEFT JOIN suggestions s ON d.day = s.day
            LEFT JOIN plans p ON d.day = p.day
            LEFT JOIN executions e ON d.day = e.day
            LEFT JOIN impacts i ON d.day = i.day
            ORDER BY d.day ASC
            </script>
            """)
    List<Map<String, Object>> queryTrendsByDay(@Param("envId") Long envId,
                                               @Param("resourcePoolId") Long resourcePoolId,
                                               @Param("startTime") OffsetDateTime startTime,
                                               @Param("endTime") OffsetDateTime endTime);

    /**
     * 按小时聚合趋势数据
     */
    @Select("""
            <script>
            WITH hour_series AS (
                SELECT generate_series(
                    DATE_TRUNC('HOUR', #{startTime}::timestamp),
                    DATE_TRUNC('HOUR', #{endTime}::timestamp),
                    INTERVAL '1 hour'
                )::timestamp as hour
            ),
            suggestions AS (
                SELECT DATE_TRUNC('HOUR', created_at)::timestamp as hour, COUNT(*) as cnt
                FROM release_resource_governance_suggestion
                WHERE 1=1
                <if test="envId != null">
                    AND env_id = #{envId}
                </if>
                <if test="resourcePoolId != null">
                    AND resource_pool_id = #{resourcePoolId}
                </if>
                AND created_at BETWEEN #{startTime} AND #{endTime}
                GROUP BY DATE_TRUNC('HOUR', created_at)::timestamp
            ),
            plans AS (
                SELECT DATE_TRUNC('HOUR', created_at)::timestamp as hour, COUNT(*) as cnt
                FROM release_resource_governance_action_plan
                WHERE 1=1
                <if test="envId != null">
                    AND env_id = #{envId}
                </if>
                <if test="resourcePoolId != null">
                    AND resource_pool_id = #{resourcePoolId}
                </if>
                AND created_at BETWEEN #{startTime} AND #{endTime}
                GROUP BY DATE_TRUNC('HOUR', created_at)::timestamp
            ),
            executions AS (
                SELECT DATE_TRUNC('HOUR', e.created_at)::timestamp as hour,
                       COUNT(*) as total,
                       SUM(CASE WHEN e.status = 'SUCCESS' THEN 1 ELSE 0 END) as success,
                       SUM(CASE WHEN e.status = 'FAILED' THEN 1 ELSE 0 END) as failed
                FROM release_resource_governance_action_execution e
                WHERE 1=1
                <if test="envId != null">
                    AND EXISTS (SELECT 1 FROM release_resource_governance_action_plan p
                                WHERE p.id = e.plan_id AND p.env_id = #{envId})
                </if>
                <if test="resourcePoolId != null">
                    AND EXISTS (SELECT 1 FROM release_resource_governance_action_plan p
                                WHERE p.id = e.plan_id AND p.resource_pool_id = #{resourcePoolId})
                </if>
                AND e.created_at BETWEEN #{startTime} AND #{endTime}
                GROUP BY DATE_TRUNC('HOUR', e.created_at)::timestamp
            ),
            impacts AS (
                SELECT DATE_TRUNC('HOUR', created_at)::timestamp as hour, COUNT(*) as cnt
                FROM release_resource_governance_record
                WHERE event_type = 'RESOURCE_GOVERNANCE_ACTION_IMPACT_RECORDED'
                <if test="envId != null">
                    AND env_id = #{envId}
                </if>
                <if test="resourcePoolId != null">
                    AND resource_pool_id = #{resourcePoolId}
                </if>
                AND created_at BETWEEN #{startTime} AND #{endTime}
                GROUP BY DATE_TRUNC('HOUR', created_at)::timestamp
            )
            SELECT
                TO_CHAR(h.hour, 'YYYY-MM-DD"T"HH24:MI:SS') as timeBucket,
                COALESCE(s.cnt, 0) as riskCount,
                COALESCE(s.cnt, 0) as suggestionCount,
                COALESCE(p.cnt, 0) as actionPlanCount,
                COALESCE(e.total, 0) as executionCount,
                COALESCE(i.cnt, 0) as impactCount,
                COALESCE(e.success, 0) as successCount,
                COALESCE(e.failed, 0) as failedCount
            FROM hour_series h
            LEFT JOIN suggestions s ON h.hour = s.hour
            LEFT JOIN plans p ON h.hour = p.hour
            LEFT JOIN executions e ON h.hour = e.hour
            LEFT JOIN impacts i ON h.hour = i.hour
            ORDER BY h.hour ASC
            </script>
            """)
    List<Map<String, Object>> queryTrendsByHour(@Param("envId") Long envId,
                                                @Param("resourcePoolId") Long resourcePoolId,
                                                @Param("startTime") OffsetDateTime startTime,
                                                @Param("endTime") OffsetDateTime endTime);

    /**
     * 按周聚合趋势数据
     */
    @Select("""
            <script>
            WITH week_series AS (
                SELECT generate_series(
                    DATE_TRUNC('WEEK', #{startTime}::timestamp),
                    DATE_TRUNC('WEEK', #{endTime}::timestamp),
                    INTERVAL '1 week'
                )::date as week
            ),
            suggestions AS (
                SELECT DATE_TRUNC('WEEK', created_at)::date as week, COUNT(*) as cnt
                FROM release_resource_governance_suggestion
                WHERE 1=1
                <if test="envId != null">
                    AND env_id = #{envId}
                </if>
                <if test="resourcePoolId != null">
                    AND resource_pool_id = #{resourcePoolId}
                </if>
                AND created_at BETWEEN #{startTime} AND #{endTime}
                GROUP BY DATE_TRUNC('WEEK', created_at)::date
            ),
            plans AS (
                SELECT DATE_TRUNC('WEEK', created_at)::date as week, COUNT(*) as cnt
                FROM release_resource_governance_action_plan
                WHERE 1=1
                <if test="envId != null">
                    AND env_id = #{envId}
                </if>
                <if test="resourcePoolId != null">
                    AND resource_pool_id = #{resourcePoolId}
                </if>
                AND created_at BETWEEN #{startTime} AND #{endTime}
                GROUP BY DATE_TRUNC('WEEK', created_at)::date
            ),
            executions AS (
                SELECT DATE_TRUNC('WEEK', e.created_at)::date as week,
                       COUNT(*) as total,
                       SUM(CASE WHEN e.status = 'SUCCESS' THEN 1 ELSE 0 END) as success,
                       SUM(CASE WHEN e.status = 'FAILED' THEN 1 ELSE 0 END) as failed
                FROM release_resource_governance_action_execution e
                WHERE 1=1
                <if test="envId != null">
                    AND EXISTS (SELECT 1 FROM release_resource_governance_action_plan p
                                WHERE p.id = e.plan_id AND p.env_id = #{envId})
                </if>
                <if test="resourcePoolId != null">
                    AND EXISTS (SELECT 1 FROM release_resource_governance_action_plan p
                                WHERE p.id = e.plan_id AND p.resource_pool_id = #{resourcePoolId})
                </if>
                AND e.created_at BETWEEN #{startTime} AND #{endTime}
                GROUP BY DATE_TRUNC('WEEK', e.created_at)::date
            ),
            impacts AS (
                SELECT DATE_TRUNC('WEEK', created_at)::date as week, COUNT(*) as cnt
                FROM release_resource_governance_record
                WHERE event_type = 'RESOURCE_GOVERNANCE_ACTION_IMPACT_RECORDED'
                <if test="envId != null">
                    AND env_id = #{envId}
                </if>
                <if test="resourcePoolId != null">
                    AND resource_pool_id = #{resourcePoolId}
                </if>
                AND created_at BETWEEN #{startTime} AND #{endTime}
                GROUP BY DATE_TRUNC('WEEK', created_at)::date
            )
            SELECT
                TO_CHAR(w.week, 'YYYY-"W"IW') as timeBucket,
                COALESCE(s.cnt, 0) as riskCount,
                COALESCE(s.cnt, 0) as suggestionCount,
                COALESCE(p.cnt, 0) as actionPlanCount,
                COALESCE(e.total, 0) as executionCount,
                COALESCE(i.cnt, 0) as impactCount,
                COALESCE(e.success, 0) as successCount,
                COALESCE(e.failed, 0) as failedCount
            FROM week_series w
            LEFT JOIN suggestions s ON w.week = s.week
            LEFT JOIN plans p ON w.week = p.week
            LEFT JOIN executions e ON w.week = e.week
            LEFT JOIN impacts i ON w.week = i.week
            ORDER BY w.week ASC
            </script>
            """)
    List<Map<String, Object>> queryTrendsByWeek(@Param("envId") Long envId,
                                                @Param("resourcePoolId") Long resourcePoolId,
                                                @Param("startTime") OffsetDateTime startTime,
                                                @Param("endTime") OffsetDateTime endTime);

    /**
     * 按月聚合趋势数据
     */
    @Select("""
            <script>
            WITH month_series AS (
                SELECT generate_series(
                    DATE_TRUNC('MONTH', #{startTime}::timestamp),
                    DATE_TRUNC('MONTH', #{endTime}::timestamp),
                    INTERVAL '1 month'
                )::date as month
            ),
            suggestions AS (
                SELECT DATE_TRUNC('MONTH', created_at)::date as month, COUNT(*) as cnt
                FROM release_resource_governance_suggestion
                WHERE 1=1
                <if test="envId != null">
                    AND env_id = #{envId}
                </if>
                <if test="resourcePoolId != null">
                    AND resource_pool_id = #{resourcePoolId}
                </if>
                AND created_at BETWEEN #{startTime} AND #{endTime}
                GROUP BY DATE_TRUNC('MONTH', created_at)::date
            ),
            plans AS (
                SELECT DATE_TRUNC('MONTH', created_at)::date as month, COUNT(*) as cnt
                FROM release_resource_governance_action_plan
                WHERE 1=1
                <if test="envId != null">
                    AND env_id = #{envId}
                </if>
                <if test="resourcePoolId != null">
                    AND resource_pool_id = #{resourcePoolId}
                </if>
                AND created_at BETWEEN #{startTime} AND #{endTime}
                GROUP BY DATE_TRUNC('MONTH', created_at)::date
            ),
            executions AS (
                SELECT DATE_TRUNC('MONTH', e.created_at)::date as month,
                       COUNT(*) as total,
                       SUM(CASE WHEN e.status = 'SUCCESS' THEN 1 ELSE 0 END) as success,
                       SUM(CASE WHEN e.status = 'FAILED' THEN 1 ELSE 0 END) as failed
                FROM release_resource_governance_action_execution e
                WHERE 1=1
                <if test="envId != null">
                    AND EXISTS (SELECT 1 FROM release_resource_governance_action_plan p
                                WHERE p.id = e.plan_id AND p.env_id = #{envId})
                </if>
                <if test="resourcePoolId != null">
                    AND EXISTS (SELECT 1 FROM release_resource_governance_action_plan p
                                WHERE p.id = e.plan_id AND p.resource_pool_id = #{resourcePoolId})
                </if>
                AND e.created_at BETWEEN #{startTime} AND #{endTime}
                GROUP BY DATE_TRUNC('MONTH', e.created_at)::date
            ),
            impacts AS (
                SELECT DATE_TRUNC('MONTH', created_at)::date as month, COUNT(*) as cnt
                FROM release_resource_governance_record
                WHERE event_type = 'RESOURCE_GOVERNANCE_ACTION_IMPACT_RECORDED'
                <if test="envId != null">
                    AND env_id = #{envId}
                </if>
                <if test="resourcePoolId != null">
                    AND resource_pool_id = #{resourcePoolId}
                </if>
                AND created_at BETWEEN #{startTime} AND #{endTime}
                GROUP BY DATE_TRUNC('MONTH', created_at)::date
            )
            SELECT
                TO_CHAR(m.month, 'YYYY-MM') as timeBucket,
                COALESCE(s.cnt, 0) as riskCount,
                COALESCE(s.cnt, 0) as suggestionCount,
                COALESCE(p.cnt, 0) as actionPlanCount,
                COALESCE(e.total, 0) as executionCount,
                COALESCE(i.cnt, 0) as impactCount,
                COALESCE(e.success, 0) as successCount,
                COALESCE(e.failed, 0) as failedCount
            FROM month_series m
            LEFT JOIN suggestions s ON m.month = s.month
            LEFT JOIN plans p ON m.month = p.month
            LEFT JOIN executions e ON m.month = e.month
            LEFT JOIN impacts i ON m.month = i.month
            ORDER BY m.month ASC
            </script>
            """)
    List<Map<String, Object>> queryTrendsByMonth(@Param("envId") Long envId,
                                                 @Param("resourcePoolId") Long resourcePoolId,
                                                 @Param("startTime") OffsetDateTime startTime,
                                                 @Param("endTime") OffsetDateTime endTime);

    // ==================== Review Items Query Methods ====================

    /**
     * 查询复盘条目 - 从建议单获取风险条目
     */
    @Select("""
            <script>
            SELECT
                'RISK' as itemType,
                s.id as itemId,
                s.suggestion_no as itemNo,
                s.title as title,
                s.risk_level as riskLevel,
                s.status as status,
                s.reason as reason,
                u.display_name as ownerName,
                s.created_at as createdAt,
                NULL as dueAt,
                NULL as relatedActionPlanNo,
                NULL as relatedExecutionNo,
                s.suggestion_no as relatedSuggestionNo
            FROM release_resource_governance_suggestion s
            LEFT JOIN sys_user u ON s.created_by = u.id
            WHERE 1=1
            <if test="envId != null">
                AND s.env_id = #{envId}
            </if>
            <if test="resourcePoolId != null">
                AND s.resource_pool_id = #{resourcePoolId}
            </if>
            <if test="serviceId != null">
                AND s.source_id = #{serviceId} AND s.source_type = 'SERVICE'
            </if>
            <if test="riskLevel != null and riskLevel != ''">
                AND s.risk_level = #{riskLevel}
            </if>
            <if test="suggestionStatus != null and suggestionStatus != ''">
                AND s.status = #{suggestionStatus}
            </if>
            </script>
            """)
    List<Map<String, Object>> queryRiskReviewItems(@Param("envId") Long envId,
                                                    @Param("resourcePoolId") Long resourcePoolId,
                                                    @Param("serviceId") Long serviceId,
                                                    @Param("riskLevel") String riskLevel,
                                                    @Param("suggestionStatus") String suggestionStatus);

    /**
     * 查询复盘条目 - 从建议单获取建议单条目
     */
    @Select("""
            <script>
            SELECT
                'SUGGESTION' as itemType,
                s.id as itemId,
                s.suggestion_no as itemNo,
                s.title as title,
                s.risk_level as riskLevel,
                s.status as status,
                s.reason as reason,
                u.display_name as ownerName,
                s.created_at as createdAt,
                NULL as dueAt,
                p.plan_no as relatedActionPlanNo,
                e.execution_no as relatedExecutionNo,
                s.suggestion_no as relatedSuggestionNo
            FROM release_resource_governance_suggestion s
            LEFT JOIN sys_user u ON s.created_by = u.id
            LEFT JOIN release_resource_governance_action_plan p ON p.suggestion_id = s.id
            LEFT JOIN release_resource_governance_action_execution e ON e.plan_id = p.id
            WHERE 1=1
            <if test="envId != null">
                AND s.env_id = #{envId}
            </if>
            <if test="resourcePoolId != null">
                AND s.resource_pool_id = #{resourcePoolId}
            </if>
            <if test="serviceId != null">
                AND s.source_id = #{serviceId} AND s.source_type = 'SERVICE'
            </if>
            <if test="riskLevel != null and riskLevel != ''">
                AND s.risk_level = #{riskLevel}
            </if>
            <if test="suggestionStatus != null and suggestionStatus != ''">
                AND s.status = #{suggestionStatus}
            </if>
            </script>
            """)
    List<Map<String, Object>> querySuggestionReviewItems(@Param("envId") Long envId,
                                                          @Param("resourcePoolId") Long resourcePoolId,
                                                          @Param("serviceId") Long serviceId,
                                                          @Param("riskLevel") String riskLevel,
                                                          @Param("suggestionStatus") String suggestionStatus);

    /**
     * 查询复盘条目 - 从动作计划获取条目
     */
    @Select("""
            <script>
            SELECT
                'ACTION_PLAN' as itemType,
                p.id as itemId,
                p.plan_no as itemNo,
                p.plan_no as title,
                p.risk_level as riskLevel,
                p.status as status,
                p.reason as reason,
                u.display_name as ownerName,
                p.created_at as createdAt,
                p.executed_at as dueAt,
                p.plan_no as relatedActionPlanNo,
                e.execution_no as relatedExecutionNo,
                s.suggestion_no as relatedSuggestionNo
            FROM release_resource_governance_action_plan p
            LEFT JOIN sys_user u ON p.created_by = u.id
            LEFT JOIN release_resource_governance_action_execution e ON e.plan_id = p.id
            LEFT JOIN release_resource_governance_suggestion s ON s.id = p.suggestion_id
            WHERE 1=1
            <if test="envId != null">
                AND p.env_id = #{envId}
            </if>
            <if test="resourcePoolId != null">
                AND p.resource_pool_id = #{resourcePoolId}
            </if>
            <if test="actionPlanStatus != null and actionPlanStatus != ''">
                AND p.status = #{actionPlanStatus}
            </if>
            </script>
            """)
    List<Map<String, Object>> queryActionPlanReviewItems(@Param("envId") Long envId,
                                                          @Param("resourcePoolId") Long resourcePoolId,
                                                          @Param("actionPlanStatus") String actionPlanStatus);

    /**
     * 查询复盘条目 - 从执行记录获取条目
     */
    @Select("""
            <script>
            SELECT
                'EXECUTION' as itemType,
                e.id as itemId,
                e.execution_no as itemNo,
                e.execution_no as title,
                p.risk_level as riskLevel,
                e.status as status,
                e.failure_reason as reason,
                u.display_name as ownerName,
                e.created_at as createdAt,
                e.finished_at as dueAt,
                p.plan_no as relatedActionPlanNo,
                e.execution_no as relatedExecutionNo,
                s.suggestion_no as relatedSuggestionNo
            FROM release_resource_governance_action_execution e
            LEFT JOIN sys_user u ON e.operator_id = u.id
            LEFT JOIN release_resource_governance_action_plan p ON p.id = e.plan_id
            LEFT JOIN release_resource_governance_suggestion s ON s.id = e.suggestion_id
            WHERE 1=1
            <if test="envId != null">
                AND p.env_id = #{envId}
            </if>
            <if test="resourcePoolId != null">
                AND p.resource_pool_id = #{resourcePoolId}
            </if>
            <if test="executionStatus != null and executionStatus != ''">
                AND e.status = #{executionStatus}
            </if>
            </script>
            """)
    List<Map<String, Object>> queryExecutionReviewItems(@Param("envId") Long envId,
                                                        @Param("resourcePoolId") Long resourcePoolId,
                                                        @Param("executionStatus") String executionStatus);

    /**
     * 查询复盘条目 - 从影响记录获取条目
     */
    @Select("""
            <script>
            SELECT
                'IMPACT' as itemType,
                r.id as itemId,
                r.id::text as itemNo,
                '影响记录' as title,
                NULL as riskLevel,
                r.result_status as status,
                r.reason as reason,
                NULL as ownerName,
                r.created_at as createdAt,
                NULL as dueAt,
                r.action_plan_no as relatedActionPlanNo,
                r.action_execution_no as relatedExecutionNo,
                r.suggestion_no as relatedSuggestionNo
            FROM release_resource_governance_record r
            WHERE r.event_type = 'RESOURCE_GOVERNANCE_ACTION_IMPACT_RECORDED'
            <if test="envId != null">
                AND r.env_id = #{envId}
            </if>
            <if test="resourcePoolId != null">
                AND r.resource_pool_id = #{resourcePoolId}
            </if>
            <if test="impactType != null and impactType != ''">
                AND EXISTS (
                    SELECT 1 FROM release_resource_governance_action_execution e
                    WHERE e.execution_no = r.action_execution_no
                    AND e.impact_type = #{impactType}
                )
            </if>
            </script>
            """)
    List<Map<String, Object>> queryImpactReviewItems(@Param("envId") Long envId,
                                                      @Param("resourcePoolId") Long resourcePoolId,
                                                      @Param("impactType") String impactType);

    // ==================== Alert Evaluation Support Methods ====================

    /**
     * 统计超时建议单数（用于告警评估）
     * 判断建议单从创建到现在是否超过指定的 SLA 时长
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM release_resource_governance_suggestion
            WHERE status = 'OPEN'
            AND EXTRACT(EPOCH FROM (#{now} - created_at)) * 1000 &gt; #{slaTargetMs}
            <if test="envId != null">
                AND env_id = #{envId}
            </if>
            <if test="resourcePoolId != null">
                AND resource_pool_id = #{resourcePoolId}
            </if>
            </script>
            """)
    Long countOverdueSuggestions(@Param("envId") Long envId,
                                  @Param("resourcePoolId") Long resourcePoolId,
                                  @Param("slaTargetMs") Long slaTargetMs,
                                  @Param("startTime") OffsetDateTime startTime,
                                  @Param("now") OffsetDateTime now);
}

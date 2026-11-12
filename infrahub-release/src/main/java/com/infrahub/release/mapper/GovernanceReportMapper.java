package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.release.entity.GovernanceReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;

/**
 * 治理运营报告 Mapper
 * Contract: v2-stage-m-contract.md
 */
@Mapper
public interface GovernanceReportMapper extends BaseMapper<GovernanceReport> {

    /**
     * 分页查询报告列表
     * Contract: GET /api/assets/governance-ops/reports
     */
    @Select("""
            <script>
            SELECT * FROM release_governance_report
            WHERE 1=1
            <if test="reportType != null and reportType != ''">
                AND report_type = #{reportType}
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
                AND start_time &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND end_time &lt;= #{endTime}
            </if>
            ORDER BY generated_at DESC
            </script>
            """)
    Page<GovernanceReport> queryReports(Page<GovernanceReport> page,
                                         @Param("reportType") String reportType,
                                         @Param("envId") Long envId,
                                         @Param("resourcePoolId") Long resourcePoolId,
                                         @Param("serviceId") Long serviceId,
                                         @Param("startTime") OffsetDateTime startTime,
                                         @Param("endTime") OffsetDateTime endTime);

    /**
     * 根据报告编号查询
     */
    @Select("SELECT * FROM release_governance_report WHERE report_no = #{reportNo}")
    GovernanceReport selectByReportNo(@Param("reportNo") String reportNo);
}
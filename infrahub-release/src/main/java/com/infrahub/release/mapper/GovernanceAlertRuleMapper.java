package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.release.entity.GovernanceAlertRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 告警规则 Mapper
 * Contract: v2-stage-m-contract.md
 */
@Mapper
public interface GovernanceAlertRuleMapper extends com.baomidou.mybatisplus.core.mapper.BaseMapper<GovernanceAlertRule> {

    /**
     * 查询所有启用的告警规则
     */
    @Select("""
            SELECT * FROM release_governance_alert_rule
            WHERE enabled = true
            ORDER BY created_at DESC
            """)
    List<GovernanceAlertRule> selectAllEnabledRules();

    /**
     * 分页查询符合筛选条件的告警规则
     */
    @Select("""
            <script>
            SELECT * FROM release_governance_alert_rule
            WHERE 1=1
            <if test="metricType != null and metricType != ''">
                AND metric_type = #{metricType}
            </if>
            <if test="alertLevel != null and alertLevel != ''">
                AND alert_level = #{alertLevel}
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
            <if test="enabled != null">
                AND enabled = #{enabled}
            </if>
            ORDER BY created_at DESC
            </script>
            """)
    Page<GovernanceAlertRule> queryRulesPage(Page<GovernanceAlertRule> page,
                                              @Param("metricType") String metricType,
                                              @Param("alertLevel") String alertLevel,
                                              @Param("envId") Long envId,
                                              @Param("resourcePoolId") Long resourcePoolId,
                                              @Param("serviceId") Long serviceId,
                                              @Param("enabled") Boolean enabled);

    /**
     * 查询符合筛选条件的告警规则（不分页，兼容旧接口）
     */
    @Select("""
            <script>
            SELECT * FROM release_governance_alert_rule
            WHERE 1=1
            <if test="metricType != null and metricType != ''">
                AND metric_type = #{metricType}
            </if>
            <if test="alertLevel != null and alertLevel != ''">
                AND alert_level = #{alertLevel}
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
            <if test="enabled != null">
                AND enabled = #{enabled}
            </if>
            ORDER BY created_at DESC
            </script>
            """)
    List<GovernanceAlertRule> queryRules(@Param("metricType") String metricType,
                                          @Param("alertLevel") String alertLevel,
                                          @Param("envId") Long envId,
                                          @Param("resourcePoolId") Long resourcePoolId,
                                          @Param("serviceId") Long serviceId,
                                          @Param("enabled") Boolean enabled);

    /**
     * 查询指定范围上下文的启用规则
     * 用于告警评估时筛选规则
     */
    @Select("""
            <script>
            SELECT * FROM release_governance_alert_rule
            WHERE enabled = true
            AND (
                (env_id IS NULL AND resource_pool_id IS NULL AND service_id IS NULL)
                OR (env_id IS NULL AND resource_pool_id IS NULL AND service_id = #{serviceId})
                OR (env_id IS NULL AND resource_pool_id = #{resourcePoolId} AND service_id IS NULL)
                OR (env_id IS NULL AND resource_pool_id = #{resourcePoolId} AND service_id = #{serviceId})
                OR (env_id = #{envId} AND resource_pool_id IS NULL AND service_id IS NULL)
                OR (env_id = #{envId} AND resource_pool_id IS NULL AND service_id = #{serviceId})
                OR (env_id = #{envId} AND resource_pool_id = #{resourcePoolId} AND service_id IS NULL)
                OR (env_id = #{envId} AND resource_pool_id = #{resourcePoolId} AND service_id = #{serviceId})
            )
            ORDER BY created_at DESC
            </script>
            """)
    List<GovernanceAlertRule> selectEnabledRulesForScope(@Param("envId") Long envId,
                                                          @Param("resourcePoolId") Long resourcePoolId,
                                                          @Param("serviceId") Long serviceId);
}
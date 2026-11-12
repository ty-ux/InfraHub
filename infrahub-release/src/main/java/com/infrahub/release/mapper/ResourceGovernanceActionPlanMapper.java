package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.release.entity.ResourceGovernanceActionPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ResourceGovernanceActionPlanMapper extends com.baomidou.mybatisplus.core.mapper.BaseMapper<ResourceGovernanceActionPlan> {

    @Select("""
            <script>
            SELECT * FROM release_resource_governance_action_plan
            WHERE 1=1
            <if test="resourcePoolId != null">
                AND resource_pool_id = #{resourcePoolId}
            </if>
            <if test="envId != null">
                AND env_id = #{envId}
            </if>
            <if test="actionType != null and actionType != ''">
                AND action_type = #{actionType}
            </if>
            <if test="status != null and status != ''">
                AND status = #{status}
            </if>
            ORDER BY created_at DESC
            </script>
            """)
    Page<ResourceGovernanceActionPlan> queryActionPlans(Page<ResourceGovernanceActionPlan> page,
                                                         @Param("resourcePoolId") Long resourcePoolId,
                                                         @Param("envId") Long envId,
                                                         @Param("actionType") String actionType,
                                                         @Param("status") String status);

    /**
     * 查询指定建议单的活跃计划（DRAFT或CONFIRMED状态）
     */
    @Select("""
            SELECT * FROM release_resource_governance_action_plan
            WHERE suggestion_id = #{suggestionId}
            AND status IN ('DRAFT', 'CONFIRMED')
            ORDER BY created_at DESC
            LIMIT 1
            """)
    ResourceGovernanceActionPlan selectActiveBySuggestionId(@Param("suggestionId") Long suggestionId);
}

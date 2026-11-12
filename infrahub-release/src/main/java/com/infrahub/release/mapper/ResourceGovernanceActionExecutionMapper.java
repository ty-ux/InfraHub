package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.release.entity.ResourceGovernanceActionExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ResourceGovernanceActionExecutionMapper extends com.baomidou.mybatisplus.core.mapper.BaseMapper<ResourceGovernanceActionExecution> {

    @Select("""
            SELECT * FROM release_resource_governance_action_execution
            WHERE plan_id = #{planId}
            ORDER BY created_at DESC
            """)
    Page<ResourceGovernanceActionExecution> selectByPlanId(Page<ResourceGovernanceActionExecution> page,
                                                            @Param("planId") Long planId);

    @Select("""
            SELECT * FROM release_resource_governance_action_execution
            WHERE plan_id = #{planId}
            AND status = 'RUNNING'
            LIMIT 1
            """)
    ResourceGovernanceActionExecution selectRunningByPlanId(@Param("planId") Long planId);
}
package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.release.entity.GovernanceAlertAssignmentRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 告警分派记录 Mapper
 * Contract: v2-stage-o-contract.md Section 8.2
 */
@Mapper
public interface GovernanceAlertAssignmentRecordMapper extends com.baomidou.mybatisplus.core.mapper.BaseMapper<GovernanceAlertAssignmentRecord> {

    /**
     * 按告警ID分页查询分派记录
     */
    @Select("""
            SELECT * FROM release_governance_alert_assignment_record
            WHERE alert_id = #{alertId}
            ORDER BY created_at DESC
            """)
    Page<GovernanceAlertAssignmentRecord> queryByAlertId(Page<GovernanceAlertAssignmentRecord> page,
                                                          @Param("alertId") Long alertId);

    /**
     * 按分派人ID分页查询分派记录（my-assigned 查询）
     */
    @Select("""
            SELECT * FROM release_governance_alert_assignment_record
            WHERE to_assignee_id = #{assigneeId}
            ORDER BY created_at DESC
            """)
    Page<GovernanceAlertAssignmentRecord> queryByAssigneeId(Page<GovernanceAlertAssignmentRecord> page,
                                                             @Param("assigneeId") Long assigneeId);
}

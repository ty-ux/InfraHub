package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.release.entity.GovernanceAlertEscalationRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 告警升级记录 Mapper
 * Contract: v2-stage-o-contract.md Section 8.3
 */
@Mapper
public interface GovernanceAlertEscalationRecordMapper extends com.baomidou.mybatisplus.core.mapper.BaseMapper<GovernanceAlertEscalationRecord> {

    /**
     * 按告警ID分页查询升级记录
     */
    @Select("""
            SELECT * FROM release_governance_alert_escalation_record
            WHERE alert_id = #{alertId}
            ORDER BY created_at DESC
            """)
    Page<GovernanceAlertEscalationRecord> queryByAlertId(Page<GovernanceAlertEscalationRecord> page,
                                                          @Param("alertId") Long alertId);

    /**
     * 按告警ID查询所有升级记录（不分页）
     */
    @Select("""
            SELECT * FROM release_governance_alert_escalation_record
            WHERE alert_id = #{alertId}
            ORDER BY created_at DESC
            """)
    List<GovernanceAlertEscalationRecord> listByAlertId(@Param("alertId") Long alertId);
}

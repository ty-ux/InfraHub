package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.release.entity.GovernanceAlertHandlingRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 告警处理记录 Mapper
 * Contract: v2-stage-n-contract.md Section 8.2
 */
@Mapper
public interface GovernanceAlertHandlingRecordMapper extends com.baomidou.mybatisplus.core.mapper.BaseMapper<GovernanceAlertHandlingRecord> {

    /**
     * 按告警ID分页查询处理记录
     */
    @Select("""
            SELECT * FROM release_governance_alert_handling_record
            WHERE alert_id = #{alertId}
            ORDER BY created_at DESC
            """)
    Page<GovernanceAlertHandlingRecord> queryByAlertId(Page<GovernanceAlertHandlingRecord> page,
                                                        @Param("alertId") Long alertId);

    /**
     * 按告警ID查询所有处理记录（不分页）
     */
    @Select("""
            SELECT * FROM release_governance_alert_handling_record
            WHERE alert_id = #{alertId}
            ORDER BY created_at DESC
            """)
    List<GovernanceAlertHandlingRecord> listByAlertId(@Param("alertId") Long alertId);
}

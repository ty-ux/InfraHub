package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.release.entity.ReleaseCanaryGateEvaluation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ReleaseCanaryGateEvaluationMapper extends BaseMapper<ReleaseCanaryGateEvaluation> {

    @Select("""
            SELECT * FROM release_canary_gate_evaluation
            WHERE batch_id = #{batchId}
            ORDER BY evaluated_at DESC
            """)
    List<ReleaseCanaryGateEvaluation> findByBatchIdOrderByEvaluatedAtDesc(@Param("batchId") Long batchId);

    @Select("""
            SELECT COUNT(1) FROM release_canary_gate_evaluation
            WHERE batch_id = #{batchId}
            """)
    long countByBatchId(@Param("batchId") Long batchId);
}
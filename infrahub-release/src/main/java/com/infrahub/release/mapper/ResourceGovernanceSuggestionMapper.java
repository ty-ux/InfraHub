package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.release.entity.ResourceGovernanceSuggestion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ResourceGovernanceSuggestionMapper extends com.baomidou.mybatisplus.core.mapper.BaseMapper<ResourceGovernanceSuggestion> {

    @Select("""
            <script>
            SELECT * FROM release_resource_governance_suggestion
            WHERE 1=1
            <if test="resourcePoolId != null">
                AND resource_pool_id = #{resourcePoolId}
            </if>
            <if test="envId != null">
                AND env_id = #{envId}
            </if>
            <if test="riskLevel != null and riskLevel != ''">
                AND risk_level = #{riskLevel}
            </if>
            <if test="suggestionType != null and suggestionType != ''">
                AND suggestion_type = #{suggestionType}
            </if>
            <if test="status != null and status != ''">
                AND status = #{status}
            </if>
            ORDER BY created_at DESC
            </script>
            """)
    Page<ResourceGovernanceSuggestion> querySuggestions(Page<ResourceGovernanceSuggestion> page,
                                                          @Param("resourcePoolId") Long resourcePoolId,
                                                          @Param("envId") Long envId,
                                                          @Param("riskLevel") String riskLevel,
                                                          @Param("suggestionType") String suggestionType,
                                                          @Param("status") String status);
}

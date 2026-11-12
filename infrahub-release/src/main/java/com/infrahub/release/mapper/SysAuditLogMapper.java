package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.release.entity.SysAuditLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface SysAuditLogMapper extends BaseMapper<SysAuditLog> {

    @Insert("""
            INSERT INTO sys_audit_log(operator_id, biz_type, biz_id, action, before_data, after_data, result, ip)
            VALUES (#{operatorId}, #{bizType}, #{bizId}, #{action}, #{beforeData}::jsonb, #{afterData}::jsonb, #{result}, #{ip})
            """)
    void insertAuditLog(@Param("operatorId") Long operatorId,
                        @Param("bizType") String bizType,
                        @Param("bizId") Long bizId,
                        @Param("action") String action,
                        @Param("beforeData") String beforeData,
                        @Param("afterData") String afterData,
                        @Param("result") String result,
                        @Param("ip") String ip);

    @Select("""
            <script>
            SELECT id, operator_id, biz_type, biz_id, action, before_data, after_data, result, ip, created_at
            FROM sys_audit_log
            WHERE 1=1
            <if test="bizType != null and bizType != ''">
                AND biz_type = #{bizType}
            </if>
            <if test="bizId != null">
                AND biz_id = #{bizId}
            </if>
            <if test="action != null and action != ''">
                AND action = #{action}
            </if>
            <if test="result != null and result != ''">
                AND result = #{result}
            </if>
            <if test="operatorId != null">
                AND operator_id = #{operatorId}
            </if>
            <if test="startTime != null">
                AND created_at &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND created_at &lt;= #{endTime}
            </if>
            ORDER BY created_at DESC
            </script>
            """)
    Page<SysAuditLog> queryAuditLogs(Page<SysAuditLog> page,
                                     @Param("bizType") String bizType,
                                     @Param("bizId") Long bizId,
                                     @Param("action") String action,
                                     @Param("result") String result,
                                     @Param("operatorId") Long operatorId,
                                     @Param("startTime") OffsetDateTime startTime,
                                     @Param("endTime") OffsetDateTime endTime);
}

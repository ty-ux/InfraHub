package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.release.entity.ResourcePool;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

@Mapper
public interface ResourcePoolMapper extends BaseMapper<ResourcePool> {

    @Select("""
            SELECT COUNT(1)
            FROM asset_host
            WHERE resource_pool_id = #{resourcePoolId}
            """)
    long countHostsByPoolId(@Param("resourcePoolId") Long resourcePoolId);

    /**
     * 统计在线主机数（互斥规则，优先 agent_status）：
     * 1. agent_status = 'ONLINE' → online（无论 host_status）
     * 2. agent_status IS NULL 或非标准值，且 host_status = 'ONLINE' → online
     */
    @Select("""
            SELECT COUNT(1)
            FROM asset_host
            WHERE resource_pool_id = #{resourcePoolId}
              AND (
                  agent_status = 'ONLINE'
                  OR (
                      (agent_status IS NULL OR agent_status NOT IN ('ONLINE', 'OFFLINE'))
                      AND host_status = 'ONLINE'
                  )
              )
            """)
    long countOnlineHostsByPoolId(@Param("resourcePoolId") Long resourcePoolId);

    /**
     * 统计离线主机数（互斥规则，优先 agent_status）：
     * 1. agent_status = 'OFFLINE' → offline（无论 host_status）
     * 2. agent_status IS NULL 或非标准值，且 host_status = 'OFFLINE' → offline
     */
    @Select("""
            SELECT COUNT(1)
            FROM asset_host
            WHERE resource_pool_id = #{resourcePoolId}
              AND (
                  agent_status = 'OFFLINE'
                  OR (
                      (agent_status IS NULL OR agent_status NOT IN ('ONLINE', 'OFFLINE'))
                      AND host_status = 'OFFLINE'
                  )
              )
            """)
    long countOfflineHostsByPoolId(@Param("resourcePoolId") Long resourcePoolId);

    /**
     * 统计未知状态主机数：
     * agent_status 无法判定，且 host_status 非 ONLINE/OFFLINE（如 MAINTENANCE、NULL 等）
     */
    @Select("""
            SELECT COUNT(1)
            FROM asset_host
            WHERE resource_pool_id = #{resourcePoolId}
              AND (agent_status IS NULL OR agent_status NOT IN ('ONLINE', 'OFFLINE'))
              AND (host_status IS NULL OR host_status NOT IN ('ONLINE', 'OFFLINE'))
            """)
    long countUnknownHostsByPoolId(@Param("resourcePoolId") Long resourcePoolId);

    @Select("""
            SELECT COUNT(1)
            FROM runtime_service_instance rsi
            INNER JOIN asset_host h ON h.id = rsi.host_id
            WHERE h.resource_pool_id = #{resourcePoolId}
            """)
    long countRuntimeInstancesByPoolId(@Param("resourcePoolId") Long resourcePoolId);

    @Select("""
            SELECT env_id
            FROM asset_host
            WHERE resource_pool_id = #{resourcePoolId}
              AND env_id IS NOT NULL
            LIMIT 1
            """)
    Optional<Long> findFirstEnvIdByPoolId(@Param("resourcePoolId") Long resourcePoolId);
}

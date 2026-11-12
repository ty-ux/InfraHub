package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.release.entity.AssetHostSshConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AssetHostSshConfigMapper extends BaseMapper<AssetHostSshConfig> {

    /**
     * 写回时按主机读取**当前启用**的 SSH 配置，并加行锁。
     *
     * <p>行锁不是装饰：编辑配置走的是普通 {@code UPDATE}，那也需要同一把行锁，因此本方法返回之前
     * 任何"改配置"都无法提交 —— 从而让"比对配置版本 → 写回"成为一个真正原子的判断，
     * 而不是"事务外先查一次、再按 id 无条件更新"。
     *
     * <p>{@code enabled = true} 过滤同时承担"禁用"与"删除"两种失效：配置被禁用或删除后这里读不到，
     * 调用方即可拒收旧样本。
     *
     * <p>返回列表而非单条：历史数据允许同一主机存在多条启用配置，调用方自行取用与采样一致的语义
     * （本模块按 id 升序取首条，与 {@code getEnabledByHostId} 保持一致）。
     */
    @Select("""
            SELECT id, host_id, ssh_port, auth_type, username, enabled, created_at, updated_at
            FROM asset_host_ssh_config
            WHERE host_id = #{hostId} AND enabled = true
            ORDER BY id
            FOR UPDATE
            """)
    @Options(useCache = false)
    List<AssetHostSshConfig> selectEnabledByHostIdForUpdate(@Param("hostId") Long hostId);
}

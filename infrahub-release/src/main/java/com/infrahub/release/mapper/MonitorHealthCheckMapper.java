package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.release.entity.MonitorHealthCheck;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MonitorHealthCheckMapper extends BaseMapper<MonitorHealthCheck> {
    @Select("SELECT * FROM monitor_health_check WHERE id = #{id} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    MonitorHealthCheck selectForUpdate(@Param("id") Long id);
}

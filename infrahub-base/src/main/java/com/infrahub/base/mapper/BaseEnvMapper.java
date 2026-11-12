package com.infrahub.base.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.base.entity.BaseEnv;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BaseEnvMapper extends BaseMapper<BaseEnv> {
    @Select("SELECT id FROM base_env WHERE id = #{id} FOR UPDATE")
    Long lockById(Long id);
}

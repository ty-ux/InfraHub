package com.infrahub.base.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.base.entity.ConfigGroup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ConfigGroupMapper extends BaseMapper<ConfigGroup> {
    @Select("SELECT * FROM config_group WHERE id = #{id} FOR UPDATE")
    ConfigGroup lockById(Long id);
}

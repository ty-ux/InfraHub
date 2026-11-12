package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.release.entity.CapacityPolicy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CapacityPolicyMapper extends BaseMapper<CapacityPolicy> {
}

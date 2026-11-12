package com.infrahub.base.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.infrahub.base.entity.ConfigGroup;
import com.infrahub.base.mapper.ConfigGroupMapper;
import org.springframework.stereotype.Service;

@Service
public class ConfigGroupService extends ServiceImpl<ConfigGroupMapper, ConfigGroup> {
}

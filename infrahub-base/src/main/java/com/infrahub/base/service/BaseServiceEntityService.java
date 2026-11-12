package com.infrahub.base.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.infrahub.base.entity.BaseServiceEntity;
import com.infrahub.base.mapper.BaseServiceMapper;
import org.springframework.stereotype.Service;

@Service
public class BaseServiceEntityService extends ServiceImpl<BaseServiceMapper, BaseServiceEntity> {
}

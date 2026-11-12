package com.infrahub.base.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.infrahub.base.entity.BaseApp;
import com.infrahub.base.mapper.BaseAppMapper;
import org.springframework.stereotype.Service;

@Service
public class BaseAppService extends ServiceImpl<BaseAppMapper, BaseApp> {
}

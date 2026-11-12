package com.infrahub.system.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.infrahub.system.entity.SysUser;
import com.infrahub.system.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

@Service
public class SysUserService extends ServiceImpl<SysUserMapper, SysUser> {

    public SysUser getByUsername(String username) {
        return lambdaQuery().eq(SysUser::getUsername, username).one();
    }

    public Set<String> getAuthorities(Long userId) {
        Set<String> authorities = new HashSet<>(baseMapper.selectPermissionCodesByUserId(userId));
        baseMapper.selectRoleCodesByUserId(userId)
                .forEach(roleCode -> authorities.add("ROLE_" + roleCode));
        return authorities;
    }
}

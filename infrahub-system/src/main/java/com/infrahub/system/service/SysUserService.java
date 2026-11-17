package com.infrahub.system.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.infrahub.common.context.ApplicationAccess;
import com.infrahub.common.context.UserContext;
import com.infrahub.system.entity.SysUser;
import com.infrahub.system.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

@Service
public class SysUserService extends ServiceImpl<SysUserMapper, SysUser> {

    public SysUser getByUsername(String username) {
        return lambdaQuery().eq(SysUser::getUsername, username).one();
    }

    public UserContext getActiveContext(SysUser user) {
        Set<String> global = new HashSet<>();
        Set<String> authorities = new HashSet<>();
        var scopes = new HashMap<String, Set<Long>>();
        for (var grant : baseMapper.selectGrants(user.getId())) {
            authorities.add("ROLE_" + grant.roleCode());
            if (grant.permCode() == null) continue;
            if ("ALL".equals(grant.appScope())) {
                global.add(grant.permCode());
            } else if (grant.appId() != null && ApplicationAccess.SCOPED_PERMISSIONS.contains(grant.permCode())) {
                scopes.computeIfAbsent(grant.permCode(), key -> new HashSet<>()).add(grant.appId());
            }
        }
        authorities.addAll(global);
        authorities.addAll(scopes.keySet());
        return UserContext.builder().userId(user.getId()).username(user.getUsername())
                .displayName(user.getDisplayName()).mustChangePassword(user.isMustChangePassword())
                .credentialVersion(user.getCredentialVersion()).authorities(authorities)
                .allAppPermissions(global).applicationPermissions(scopes).build();
    }
}

package com.infrahub.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.system.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("""
        select distinct r.role_code, p.perm_code, r.app_scope, b.id as app_id
        from sys_user_role ur
        join sys_role r on r.id = ur.role_id and r.status = 'ENABLED'
        left join sys_role_permission rp on rp.role_id = r.id
        left join sys_permission p on p.id = rp.permission_id
        left join sys_role_app a on a.role_id = r.id and r.app_scope = 'SELECTED'
        left join base_app b on b.id = a.app_id and b.is_deleted = false
        where ur.user_id = #{userId}
        """)
    List<Grant> selectGrants(Long userId);

    record Grant(String roleCode, String permCode, String appScope, Long appId) {
    }
}

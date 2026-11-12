package com.infrahub.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.system.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("""
        select distinct p.perm_code
        from sys_user_role ur
        join sys_role_permission rp on rp.role_id = ur.role_id
        join sys_permission p on p.id = rp.permission_id
        where ur.user_id = #{userId}
        """)
    List<String> selectPermissionCodesByUserId(Long userId);

    @Select("""
        select distinct r.role_code
        from sys_user_role ur
        join sys_role r on r.id = ur.role_id
        where ur.user_id = #{userId}
        """)
    List<String> selectRoleCodesByUserId(Long userId);
}

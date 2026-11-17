package com.infrahub.system.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface SysAccessMapper {
    @Select("SELECT role_id FROM sys_user_role WHERE user_id = #{userId} ORDER BY role_id")
    List<Long> userRoles(Long userId);

    @Select("SELECT permission_id FROM sys_role_permission WHERE role_id = #{roleId} ORDER BY permission_id")
    List<Long> rolePermissions(Long roleId);

    @Select("SELECT app_id FROM sys_role_app WHERE role_id = #{roleId} ORDER BY app_id")
    List<Long> roleApps(Long roleId);

    @Select("""
            SELECT id, app_code, app_name FROM base_app WHERE is_deleted = false ORDER BY app_name, id
            """)
    List<AppOption> apps();

    @Select("SELECT id FROM sys_user WHERE id = #{id} FOR UPDATE")
    Long lockUser(Long id);

    @Select("SELECT id FROM sys_role WHERE id = #{id} FOR UPDATE")
    Long lockRole(Long id);

    @Select("SELECT pg_advisory_xact_lock(93821, 1)::text")
    String lockGrantChanges();

    @Delete("DELETE FROM sys_user_role WHERE user_id = #{userId}")
    void clearUserRoles(Long userId);

    @Insert("INSERT INTO sys_user_role (user_id, role_id) VALUES (#{userId}, #{roleId})")
    void addUserRole(Long userId, Long roleId);

    @Delete("DELETE FROM sys_role_permission WHERE role_id = #{roleId}")
    void clearRolePermissions(Long roleId);

    @Insert("INSERT INTO sys_role_permission (role_id, permission_id) VALUES (#{roleId}, #{permissionId})")
    void addRolePermission(Long roleId, Long permissionId);

    @Delete("DELETE FROM sys_role_app WHERE role_id = #{roleId}")
    void clearRoleApps(Long roleId);

    @Insert("INSERT INTO sys_role_app (role_id, app_id) VALUES (#{roleId}, #{appId})")
    void addRoleApp(Long roleId, Long appId);

    record AppOption(Long id, String appCode, String appName) {
    }
}

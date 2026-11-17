package com.infrahub.system.service;

import com.infrahub.common.context.ApplicationAccess;
import com.infrahub.common.context.UserContextHolder;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.system.entity.SysPermission;
import com.infrahub.system.entity.SysRole;
import com.infrahub.system.entity.SysUser;
import com.infrahub.system.mapper.SysAccessMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SysAccessService {
    private final SysAccessMapper mapper;
    private final SysRoleService roles;
    private final SysUserService users;
    private final SysPermissionService permissions;
    private final PasswordEncoder passwordEncoder;

    public Catalog catalog() {
        return new Catalog(roles.lambdaQuery().orderByAsc(SysRole::getId).list(),
                permissions.lambdaQuery().orderByAsc(SysPermission::getResourceType, SysPermission::getPermCode).list(),
                mapper.apps(), ApplicationAccess.SCOPED_PERMISSIONS);
    }

    public Set<Long> userRoles(Long userId) {
        return new HashSet<>(mapper.userRoles(userId));
    }

    @Transactional
    public Long createUser(NewUser request) {
        LocalPasswordPolicy.validate(request.initialPassword());
        if (!request.roleIds().isEmpty() && roles.listByIds(request.roleIds()).stream()
                .filter(role -> "ENABLED".equals(role.getStatus())).count() != request.roleIds().size()) {
            throw invalid("所选角色不存在或未启用");
        }
        SysUser user = new SysUser();
        user.setUsername(request.username());
        user.setDisplayName(request.displayName().trim());
        user.setAuthType("LOCAL");
        user.setPasswordHash(passwordEncoder.encode(request.initialPassword()));
        user.setStatus("ENABLED");
        user.setMustChangePassword(true);
        try {
            users.save(user);
        } catch (DuplicateKeyException exception) {
            throw invalid("用户名已存在");
        }
        request.roleIds().forEach(roleId -> mapper.addUserRole(user.getId(), roleId));
        return user.getId();
    }

    @Transactional
    public void saveUserRoles(Long userId, Set<Long> roleIds) {
        mapper.lockGrantChanges();
        if (mapper.lockUser(userId) == null) throw invalid("用户不存在");
        if (!roleIds.isEmpty() && roles.listByIds(roleIds).size() != roleIds.size()) throw invalid("角色不存在");
        mapper.clearUserRoles(userId);
        roleIds.forEach(roleId -> mapper.addUserRole(userId, roleId));
        requireCurrentAdmin();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Grants roleGrants(Long id) {
        SysRole role = roles.getById(id);
        if (role == null) throw invalid("角色不存在");
        return new Grants(role.getAppScope(), new HashSet<>(mapper.rolePermissions(id)), new HashSet<>(mapper.roleApps(id)));
    }

    @Transactional
    public void saveRoleGrants(Long id, Grants grants) {
        mapper.lockGrantChanges();
        if (mapper.lockRole(id) == null) throw invalid("角色不存在");
        List<SysPermission> selected = grants.permissionIds().isEmpty() ? List.of() : permissions.listByIds(grants.permissionIds());
        if (selected.size() != grants.permissionIds().size()) throw invalid("权限不存在");
        if ("SELECTED".equals(grants.appScope())) {
            if (selected.stream().anyMatch(permission -> !ApplicationAccess.SCOPED_PERMISSIONS.contains(permission.getPermCode()))) {
                throw invalid("所选权限需要全部应用范围");
            }
            var activeApps = mapper.apps().stream().map(SysAccessMapper.AppOption::id).collect(java.util.stream.Collectors.toSet());
            if (!activeApps.containsAll(grants.appIds())) throw invalid("所选应用不存在或已删除");
        }
        roles.lambdaUpdate().eq(SysRole::getId, id).set(SysRole::getAppScope, grants.appScope()).update();
        mapper.clearRolePermissions(id);
        grants.permissionIds().forEach(permissionId -> mapper.addRolePermission(id, permissionId));
        mapper.clearRoleApps(id);
        if ("SELECTED".equals(grants.appScope())) grants.appIds().forEach(appId -> mapper.addRoleApp(id, appId));
        requireCurrentAdmin();
    }

    @Transactional
    public Long createRole(NewRole request) {
        if (roles.lambdaQuery().eq(SysRole::getRoleCode, request.roleCode()).exists()) throw invalid("角色编码已存在");
        SysRole role = new SysRole();
        role.setRoleCode(request.roleCode());
        role.setRoleName(request.roleName().trim());
        role.setStatus("ENABLED");
        role.setAppScope("SELECTED");
        roles.save(role);
        return role.getId();
    }

    private void requireCurrentAdmin() {
        var current = users.getById(UserContextHolder.getUserIdOrDefault(null));
        if (current == null || !users.getActiveContext(current).getAuthorities().contains("SYSTEM_ADMIN")) {
            throw invalid("修改后当前账号将失去系统管理员权限");
        }
    }

    private static BizException invalid(String message) {
        return new BizException(ErrorCode.VALIDATION_ERROR, message);
    }

    public record Catalog(List<SysRole> roles, List<SysPermission> permissions, List<SysAccessMapper.AppOption> apps,
                          Set<String> scopedPermissions) {
    }

    public record Grants(@NotNull @Pattern(regexp = "ALL|SELECTED") String appScope,
                         @NotNull @Size(max = 250) Set<@NotNull @Positive Long> permissionIds,
                         @NotNull @Size(max = 1000) Set<@NotNull @Positive Long> appIds) {
    }

    public record UserRoles(@NotNull @Size(max = 250) Set<@NotNull @Positive Long> roleIds) {
    }

    public record NewRole(@NotBlank @Size(max = 64) @Pattern(regexp = "[A-Z][A-Z0-9_]*") String roleCode,
                          @NotBlank @Size(max = 128) String roleName) {
    }

    public record NewUser(@NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.-]*") String username,
                          @NotBlank @Size(max = 128) String displayName,
                          @NotBlank @Size(max = 72) String initialPassword,
                          @NotNull @Size(max = 250) Set<@NotNull @Positive Long> roleIds) {
    }
}

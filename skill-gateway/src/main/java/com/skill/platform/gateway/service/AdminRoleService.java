package com.skill.platform.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skill.platform.gateway.dal.entity.AdminPermission;
import com.skill.platform.gateway.dal.entity.AdminRole;
import com.skill.platform.gateway.dal.entity.AdminRolePermission;
import com.skill.platform.gateway.dal.mapper.AdminPermissionMapper;
import com.skill.platform.gateway.dal.mapper.AdminRoleMapper;
import com.skill.platform.gateway.dal.mapper.AdminRolePermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 后台角色 / 权限点查询（V2.1 权限模型）。
 *
 * <p>权限集合按角色 30s 进程内缓存：登录与每个请求的拦截器校验都需要读，
 * 避免每请求查库；角色权限变更最迟 30s 生效。
 */
@Service
@RequiredArgsConstructor
public class AdminRoleService {

    private static final long CACHE_TTL_MILLIS = 30_000L;

    private final AdminRoleMapper adminRoleMapper;
    private final AdminPermissionMapper adminPermissionMapper;
    private final AdminRolePermissionMapper adminRolePermissionMapper;

    private record CachedPerms(Set<String> permissions, long loadedAt) {
    }

    private final Map<String, CachedPerms> permCache = new ConcurrentHashMap<>();

    /** 角色权限点集合（30s 缓存）。 */
    public Set<String> permissionsOf(String roleCode) {
        long now = System.currentTimeMillis();
        CachedPerms cached = permCache.get(roleCode);
        if (cached != null && now - cached.loadedAt() < CACHE_TTL_MILLIS) {
            return cached.permissions();
        }
        Set<String> perms = adminRolePermissionMapper.selectList(
                new LambdaQueryWrapper<AdminRolePermission>()
                        .eq(AdminRolePermission::getRoleCode, roleCode))
                .stream().map(AdminRolePermission::getPermCode).collect(java.util.stream.Collectors.toSet());
        permCache.put(roleCode, new CachedPerms(Set.copyOf(perms), now));
        return permCache.get(roleCode).permissions();
    }

    /** 角色视图列表（含权限点）。 */
    public List<Map<String, Object>> listRoles() {
        return adminRoleMapper.selectList(new LambdaQueryWrapper<AdminRole>()
                .orderByDesc(AdminRole::getBuiltin)
                .orderByAsc(AdminRole::getRoleCode))
                .stream().map(role -> {
                    Map<String, Object> view = new LinkedHashMap<String, Object>();
                    view.put("roleCode", role.getRoleCode());
                    view.put("roleName", role.getRoleName());
                    view.put("remark", role.getRemark());
                    view.put("builtin", role.getBuiltin());
                    view.put("permissions", List.copyOf(permissionsOf(role.getRoleCode())));
                    return (Map<String, Object>) view;
                }).toList();
    }

    /** 权限点目录（前端菜单渲染数据源）。 */
    public List<Map<String, Object>> listPermissions() {
        return adminPermissionMapper.selectList(new LambdaQueryWrapper<AdminPermission>()
                .orderByAsc(AdminPermission::getGroupName)
                .orderByAsc(AdminPermission::getSortNo))
                .stream().map(perm -> {
                    Map<String, Object> view = new LinkedHashMap<String, Object>();
                    view.put("permCode", perm.getPermCode());
                    view.put("permName", perm.getPermName());
                    view.put("groupName", perm.getGroupName());
                    view.put("sortNo", perm.getSortNo());
                    return (Map<String, Object>) view;
                }).toList();
    }

    /** 角色名（登录响应展示用）。 */
    public String roleNameOf(String roleCode) {
        AdminRole role = adminRoleMapper.selectOne(new LambdaQueryWrapper<AdminRole>()
                .eq(AdminRole::getRoleCode, roleCode).last("LIMIT 1"));
        return role == null ? roleCode : role.getRoleName();
    }
}

package com.skill.platform.gateway.security;

import java.util.Set;

/**
 * 权限点常量目录（与 schema V2.1_1 admin_permission 字典一致）。
 */
public final class Permissions {

    public static final String SUPER_ADMIN = "SUPER_ADMIN";

    public static final String METRICS_READ = "metrics:read";
    public static final String SKILL_READ = "skill:read";
    public static final String SKILL_UPLOAD = "skill:upload";
    public static final String SKILL_REVIEW = "skill:review";
    public static final String SKILL_PUBLISH = "skill:publish";
    public static final String SKILL_ROLLBACK = "skill:rollback";
    public static final String SKILL_DELETE = "skill:delete";
    public static final String EXECUTION_READ = "execution:read";
    public static final String EXECUTION_MANAGE = "execution:manage";
    public static final String ALERT_READ = "alert:read";
    public static final String ALERT_MANAGE = "alert:manage";
    public static final String MODEL_READ = "model:read";
    public static final String MODEL_MANAGE = "model:manage";
    public static final String KB_MANAGE = "kb:manage";
    public static final String KEY_READ = "key:read";
    public static final String KEY_MANAGE = "key:manage";
    public static final String USER_MANAGE = "user:manage";
    public static final String SYSCONFIG_READ = "sysconfig:read";
    public static final String SYSCONFIG_MANAGE = "sysconfig:manage";
    public static final String CONSUMPTION_READ = "consumption:read";
    public static final String CONSUMPTION_MANAGE = "consumption:manage";
    public static final String ISSUE_READ = "issue:read";
    public static final String ISSUE_MANAGE = "issue:manage";

    private Permissions() {
    }

    /** 权限判定：SUPER_ADMIN 通配全部；其余按权限点精确匹配。 */
    public static boolean matches(AdminContext ctx, String permCode) {
        return SUPER_ADMIN.equals(ctx.role()) || ctx.permissions().contains(permCode);
    }

    /** 全部权限点（legacy 静态 token 视为超管时使用）。 */
    public static Set<String> all() {
        return Set.of(METRICS_READ, SKILL_READ, SKILL_UPLOAD, SKILL_REVIEW, SKILL_PUBLISH,
                SKILL_ROLLBACK, SKILL_DELETE, EXECUTION_READ, EXECUTION_MANAGE, ALERT_READ,
                ALERT_MANAGE, MODEL_READ, MODEL_MANAGE, KB_MANAGE, KEY_READ, KEY_MANAGE,
                USER_MANAGE, SYSCONFIG_READ, SYSCONFIG_MANAGE, CONSUMPTION_READ,
                CONSUMPTION_MANAGE, ISSUE_READ, ISSUE_MANAGE);
    }
}

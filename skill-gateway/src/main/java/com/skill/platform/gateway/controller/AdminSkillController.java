package com.skill.platform.gateway.controller;

import com.skill.platform.gateway.common.ApiResponse;
import com.skill.platform.gateway.security.AdminContext;
import com.skill.platform.gateway.security.Permissions;
import com.skill.platform.gateway.security.RequirePermission;
import com.skill.platform.gateway.service.SkillDtos.SkillDetail;
import com.skill.platform.gateway.service.SkillDtos.SkillPage;
import com.skill.platform.gateway.service.SkillDtos.SkillSummary;
import com.skill.platform.gateway.service.SkillService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * Skill 管理面（V2.1）：跨租户技能管理，按权限点分权——
 * 上传（skill:upload）/ 审核（skill:review）/ 上下架（skill:publish）/ 回滚（skill:rollback）。
 * 业务面 HMAC 端点保持不变（外部调用方），管理面统一走 JWT 权限管控。
 */
@RestController
@RequestMapping("/api/v1/admin/skills")
@RequiredArgsConstructor
public class AdminSkillController {

    private final SkillService skillService;

    /** 跨租户技能列表（skill:read）。 */
    @GetMapping
    @RequirePermission(Permissions.SKILL_READ)
    public ApiResponse<SkillPage> list(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String skillCode,
            @RequestParam(required = false) String visibility,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(skillService.listForAdmin(tenantId, skillCode, visibility,
                pageNo, pageSize));
    }

    /** 技能详情（版本列表含审计字段，skill:read）。 */
    @GetMapping("/{tenantId}/{skillCode}")
    @RequirePermission(Permissions.SKILL_READ)
    public ApiResponse<SkillDetail> detail(@PathVariable String tenantId,
                                           @PathVariable String skillCode) {
        return ApiResponse.ok(skillService.detailForAdmin(tenantId, skillCode));
    }

    /** 上传新版本（skill:upload；operator=admin username）。 */
    @PostMapping("/{tenantId}/{skillCode}/versions")
    @RequirePermission(Permissions.SKILL_UPLOAD)
    public ApiResponse<SkillDetail.VersionView> upload(
            @RequestAttribute(AdminContext.ATTR_ADMIN) AdminContext admin,
            @PathVariable String tenantId,
            @PathVariable String skillCode,
            @RequestParam String version,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String visibility,
            @RequestParam(required = false, name = "requiredAbilities") String requiredAbilities,
            @RequestParam(required = false, name = "outputConfig") String outputConfig,
            @RequestParam(required = false, name = "pricingConfig") String pricingConfig,
            @RequestParam(required = false, name = "invocationSpec") String invocationSpec,
            @RequestParam(required = false) String changelog,
            @RequestParam(defaultValue = "false") boolean publish,
            @RequestParam("file") MultipartFile file) throws IOException {
        return ApiResponse.ok(skillService.upload(tenantId, admin.username(), skillCode, version,
                name, description, visibility, requiredAbilities, outputConfig, pricingConfig,
                invocationSpec, changelog, publish, file.getBytes()));
    }

    /** 审核版本（skill:review）：1=通过发布 / 2=驳回废弃（附原因）。 */
    @PutMapping("/{tenantId}/{skillCode}/versions/{version}/status")
    @RequirePermission(Permissions.SKILL_REVIEW)
    public ApiResponse<SkillDetail.VersionView> review(
            @RequestAttribute(AdminContext.ATTR_ADMIN) AdminContext admin,
            @PathVariable String tenantId,
            @PathVariable String skillCode,
            @PathVariable String version,
            @RequestBody ReviewRequest request) {
        return ApiResponse.ok(skillService.reviewVersion(tenantId, skillCode, version,
                request.status(), admin.username(), request.reason()));
    }

    /** 版本回滚 / 切换默认版本（skill:rollback；目标版本须已发布）。 */
    @PutMapping("/{tenantId}/{skillCode}/default-version")
    @RequirePermission(Permissions.SKILL_ROLLBACK)
    public ApiResponse<SkillSummary> setDefaultVersion(
            @PathVariable String tenantId,
            @PathVariable String skillCode,
            @RequestBody Map<String, String> request) {
        return ApiResponse.ok(skillService.setDefaultVersion(tenantId, skillCode,
                request.get("version")));
    }

    /** 上架/下架（skill:publish）：PUBLIC / PRIVATE。 */
    @PutMapping("/{tenantId}/{skillCode}/visibility")
    @RequirePermission(Permissions.SKILL_PUBLISH)
    public ApiResponse<SkillSummary> setVisibility(
            @PathVariable String tenantId,
            @PathVariable String skillCode,
            @RequestBody Map<String, String> request) {
        return ApiResponse.ok(skillService.setVisibility(tenantId, skillCode,
                request.get("visibility")));
    }

    /** 运营配置更新（skill:publish）：outputConfig / pricingConfig 即时生效。 */
    @PutMapping("/{tenantId}/{skillCode}/config")
    @RequirePermission(Permissions.SKILL_PUBLISH)
    public ApiResponse<SkillSummary> updateConfig(
            @PathVariable String tenantId,
            @PathVariable String skillCode,
            @RequestBody com.skill.platform.gateway.service.SkillDtos.ConfigUpdateRequest request) {
        return ApiResponse.ok(skillService.updateConfig(tenantId, skillCode, request));
    }

    /** 删除 Skill（skill:delete）：硬删元数据与包对象，执行记录保留审计。 */
    @org.springframework.web.bind.annotation.DeleteMapping("/{tenantId}/{skillCode}")
    @RequirePermission(Permissions.SKILL_DELETE)
    public ApiResponse<Void> delete(@PathVariable String tenantId,
                                    @PathVariable String skillCode) {
        skillService.delete(tenantId, skillCode);
        return ApiResponse.ok(null);
    }

    /** 审核入参。 */
    public record ReviewRequest(Integer status, String reason) {
    }
}

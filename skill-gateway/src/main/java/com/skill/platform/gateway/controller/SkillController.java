package com.skill.platform.gateway.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skill.platform.gateway.common.ApiResponse;
import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.common.ErrorCode;
import com.skill.platform.gateway.dal.entity.Skill;
import com.skill.platform.gateway.dal.mapper.SkillMapper;
import com.skill.platform.gateway.security.AppKeyAuthInterceptor;
import com.skill.platform.gateway.security.CallerContext;
import com.skill.platform.gateway.service.InvocationSpecExporter;
import com.skill.platform.gateway.service.SkillDtos.ConfigUpdateRequest;
import com.skill.platform.gateway.service.SkillDtos.DefaultVersionRequest;
import com.skill.platform.gateway.service.SkillDtos.MarketplacePage;
import com.skill.platform.gateway.service.SkillDtos.PackageDownloadView;
import com.skill.platform.gateway.service.SkillDtos.SkillDetail;
import com.skill.platform.gateway.service.SkillDtos.SkillPage;
import com.skill.platform.gateway.service.SkillDtos.SkillSummary;
import com.skill.platform.gateway.service.SkillService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Skill 管理接口（§5.2）：上传（V1 运营代传，publish 标记即审核闸）、列表、详情、
 * 默认版本、运营配置即时生效、分发包下载、invocation_spec 导出。
 */
@RestController
@RequestMapping("/api/v1/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;
    private final SkillMapper skillMapper;
    private final InvocationSpecExporter specExporter;

    @PostMapping("/upload")
    public ApiResponse<SkillDetail.VersionView> upload(
            @RequestAttribute(AppKeyAuthInterceptor.ATTR_CALLER) CallerContext caller,
            @RequestParam String skillCode,
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
            @RequestParam("file") MultipartFile file) throws Exception {
        return ApiResponse.ok(skillService.upload(caller, skillCode, version, name, description,
                visibility, requiredAbilities, outputConfig, pricingConfig, invocationSpec,
                changelog, publish, file.getBytes()));
    }

    @GetMapping
    public ApiResponse<SkillPage> list(
            @RequestAttribute(AppKeyAuthInterceptor.ATTR_CALLER) CallerContext caller,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(skillService.list(caller, pageNo, pageSize));
    }

    @GetMapping("/{skillCode}")
    public ApiResponse<SkillDetail> detail(
            @RequestAttribute(AppKeyAuthInterceptor.ATTR_CALLER) CallerContext caller,
            @PathVariable String skillCode) {
        return ApiResponse.ok(skillService.detail(caller, skillCode));
    }

    @PutMapping("/{skillCode}/default-version")
    public ApiResponse<SkillSummary> setDefaultVersion(
            @RequestAttribute(AppKeyAuthInterceptor.ATTR_CALLER) CallerContext caller,
            @PathVariable String skillCode,
            @RequestBody DefaultVersionRequest request) {
        return ApiResponse.ok(skillService.setDefaultVersion(caller, skillCode, request));
    }

    @PutMapping("/{skillCode}/config")
    public ApiResponse<SkillSummary> updateConfig(
            @RequestAttribute(AppKeyAuthInterceptor.ATTR_CALLER) CallerContext caller,
            @PathVariable String skillCode,
            @RequestBody ConfigUpdateRequest request) {
        return ApiResponse.ok(skillService.updateConfig(caller, skillCode, request));
    }

    /** 版本审核状态流转（§10.1）：{status: 1=审核通过发布 / 2=驳回·下架废弃} */
    @PutMapping("/{skillCode}/versions/{version}/status")
    public ApiResponse<SkillDetail.VersionView> reviewVersion(
            @RequestAttribute(AppKeyAuthInterceptor.ATTR_CALLER) CallerContext caller,
            @PathVariable String skillCode,
            @PathVariable String version,
            @RequestBody Map<String, Object> request) {
        int status = Integer.parseInt(String.valueOf(request.get("status")));
        return ApiResponse.ok(skillService.reviewVersion(caller, skillCode, version, status));
    }

    /** 市场上下架（§10.1 市场管理）：{visibility: PUBLIC / PRIVATE}，上架需已发布版本 */
    @PutMapping("/{skillCode}/visibility")
    public ApiResponse<SkillSummary> setVisibility(
            @RequestAttribute(AppKeyAuthInterceptor.ATTR_CALLER) CallerContext caller,
            @PathVariable String skillCode,
            @RequestBody Map<String, String> request) {
        return ApiResponse.ok(skillService.setVisibility(caller, skillCode, request.get("visibility")));
    }

    /** 删除 Skill（本租户）：删元数据与版本记录并清理 COS 包对象；历史执行记录保留审计 */
    @DeleteMapping("/{skillCode}")
    public ApiResponse<Void> delete(
            @RequestAttribute(AppKeyAuthInterceptor.ATTR_CALLER) CallerContext caller,
            @PathVariable String skillCode) {
        skillService.delete(caller, skillCode);
        return ApiResponse.ok();
    }

    @GetMapping("/{skillCode}/package")
    public ApiResponse<PackageDownloadView> downloadPackage(
            @RequestAttribute(AppKeyAuthInterceptor.ATTR_CALLER) CallerContext caller,
            @PathVariable String skillCode,
            @RequestParam(required = false) String version) {
        return ApiResponse.ok(skillService.downloadPackage(caller, skillCode, version));
    }

    /** 调用说明导出（markdown / openapi / tool-schema） */
    @GetMapping("/{skillCode}/invocation-spec")
    public ApiResponse<String> invocationSpec(
            @RequestAttribute(AppKeyAuthInterceptor.ATTR_CALLER) CallerContext caller,
            @PathVariable String skillCode,
            @RequestParam(defaultValue = "markdown") String format) {
        InvocationSpecExporter.Format parsed = parseFormat(format);
        Skill skill = skillMapper.selectOne(new LambdaQueryWrapper<Skill>()
                .eq(Skill::getTenantId, caller.tenantId())
                .eq(Skill::getSkillCode, skillCode)
                .last("LIMIT 1"));
        if (skill == null) {
            // 公开 Skill 的说明对全平台可见（市场上架物料）
            skill = skillMapper.selectOne(new LambdaQueryWrapper<Skill>()
                    .eq(Skill::getSkillCode, skillCode)
                    .eq(Skill::getVisibility, Skill.VISIBILITY_PUBLIC)
                    .eq(Skill::getStatus, 1)
                    .last("LIMIT 1"));
        }
        if (skill == null) {
            throw new BizException(ErrorCode.SKILL_NOT_FOUND, "Skill 不存在: " + skillCode);
        }
        return ApiResponse.ok(specExporter.export(skill, parsed));
    }

    private InvocationSpecExporter.Format parseFormat(String format) {
        try {
            return InvocationSpecExporter.Format.valueOf(format.toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "format 仅支持 markdown / openapi / tool-schema");
        }
    }
}

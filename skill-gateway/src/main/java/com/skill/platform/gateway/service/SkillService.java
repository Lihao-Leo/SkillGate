package com.skill.platform.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.common.ErrorCode;
import com.skill.platform.gateway.common.Ids;
import com.skill.platform.gateway.dal.entity.Skill;
import com.skill.platform.gateway.dal.entity.SkillVersion;
import com.skill.platform.gateway.dal.mapper.SkillMapper;
import com.skill.platform.gateway.dal.mapper.SkillVersionMapper;
import com.skill.platform.gateway.infra.ObjectStorage;
import com.skill.platform.gateway.security.CallerContext;
import com.skill.platform.gateway.service.SkillDtos.DefaultVersionRequest;
import com.skill.platform.gateway.service.SkillDtos.MarketplaceItem;
import com.skill.platform.gateway.service.SkillDtos.MarketplacePage;
import com.skill.platform.gateway.service.SkillDtos.PackageDownloadView;
import com.skill.platform.gateway.service.SkillDtos.SkillDetail;
import com.skill.platform.gateway.service.SkillDtos.SkillPage;
import com.skill.platform.gateway.service.SkillDtos.SkillSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Skill 生命周期管理（§4.1 / §5.2）：上传校验与版本、运营配置即时生效、
 * 市场可见性、FREE 分发包下载、版本不覆盖（包永不覆盖，复现锚点 = sha256 + 镜像 digest）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillService {

    private static final Pattern VERSION_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+(-[\\w.]+)?$");
    private static final Duration PACKAGE_URL_TTL = Duration.ofHours(24);

    private final SkillMapper skillMapper;
    private final SkillVersionMapper skillVersionMapper;
    private final ObjectStorage objectStorage;
    private final RateLimitService rateLimitService;

    @Value("${skill-platform.skill.max-package-size:209715200}")
    private long maxPackageSize;

    // ------------------------------------------------------------------
    // 上传
    // ------------------------------------------------------------------

    @Transactional
    public SkillDetail.VersionView upload(CallerContext caller, String skillCode, String version,
                                          String name, String description, String visibility,
                                          String requiredAbilities, String outputConfig,
                                          String pricingConfig, String invocationSpec,
                                          String changelog, boolean publish, byte[] zipBytes) {
        if (skillCode == null || skillCode.isBlank() || !VERSION_PATTERN.matcher(version).matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "skillCode 不能为空且 version 需为语义化版本（如 1.2.0）");
        }
        for (String json : new String[]{outputConfig, pricingConfig, invocationSpec, requiredAbilities}) {
            if (json != null && !json.isBlank() && !JsonCodec.isValidJson(json)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "配置项必须为合法 JSON");
            }
        }
        if (zipBytes == null || zipBytes.length == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "Skill 包不能为空");
        }
        if (zipBytes.length > maxPackageSize) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "Skill 包超过 " + (maxPackageSize / 1024 / 1024) + "MB 上限");
        }
        zipBytes = flattenSingleRootDir(zipBytes);
        String kind = detectKind(zipBytes);

        Skill skill = skillMapper.selectOne(new LambdaQueryWrapper<Skill>()
                .eq(Skill::getTenantId, caller.tenantId())
                .eq(Skill::getSkillCode, skillCode)
                .last("LIMIT 1"));
        if (skill == null) {
            skill = new Skill();
            skill.setTenantId(caller.tenantId());
            skill.setSkillCode(skillCode);
            skill.setName(name == null || name.isBlank() ? skillCode : name);
            skill.setDescription(description);
            skill.setVisibility(visibility == null ? Skill.VISIBILITY_PRIVATE : visibility);
            skill.setRequiredAbilities(requiredAbilities);
            skill.setOutputConfig(outputConfig);
            skill.setPricingConfig(pricingConfig);
            skill.setInvocationSpec(invocationSpec);
            skill.setStatus(1);
            skill.setKind(kind);
            skill.setCreatedBy(caller.appKeyId());
            syncTelemetryToken(skill);
            skillMapper.insert(skill);
        } else {
            if (skill.getKind() != null && !skill.getKind().equals(kind)) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "包类型与已有版本不一致（" + skill.getKind() + "）：同 skillCode 不允许 CODE/AGENT 混型上传");
            }
            // 运营配置随新版本一并更新（Skill 本体改动走新版本上传，§4.1）
            skill.setName(name == null || name.isBlank() ? skill.getName() : name);
            if (description != null) {
                skill.setDescription(description);
            }
            if (visibility != null) {
                skill.setVisibility(visibility);
            }
            if (requiredAbilities != null) {
                skill.setRequiredAbilities(requiredAbilities);
            }
            if (outputConfig != null) {
                skill.setOutputConfig(outputConfig);
            }
            if (pricingConfig != null) {
                skill.setPricingConfig(pricingConfig);
                syncTelemetryToken(skill);
            }
            if (invocationSpec != null) {
                skill.setInvocationSpec(invocationSpec);
            }
            skillMapper.updateById(skill);
        }

        if (skillVersionMapper.selectCount(new LambdaQueryWrapper<SkillVersion>()
                .eq(SkillVersion::getSkillId, skill.getId())
                .eq(SkillVersion::getVersion, version)) > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "版本已存在（版本不覆盖，请递增版本号）: " + version);
        }

        String ossKey = "skill/%s/%s/%s/skill.zip".formatted(caller.tenantId(), skillCode, version);
        objectStorage.put(ossKey, zipBytes, "application/zip");
        String ossUrl = objectStorage.publicUrl(ossKey);

        String sha256 = sha256Hex(zipBytes);
        SkillVersion skillVersion = new SkillVersion();
        skillVersion.setSkillId(skill.getId());
        skillVersion.setVersion(version);
        skillVersion.setOssKey(ossKey);
        skillVersion.setOssUrl(ossUrl);
        skillVersion.setPackageSha256(sha256);
        skillVersion.setPackageSize((long) zipBytes.length);
        skillVersion.setChangelog(changelog);
        skillVersion.setStatus(publish ? SkillVersion.STATUS_PUBLISHED : SkillVersion.STATUS_UPLOADED);
        skillVersion.setUploadedBy(caller.appKeyId());
        skillVersionMapper.insert(skillVersion);

        if (publish && (skill.getDefaultVersion() == null || skill.getDefaultVersion().isBlank())) {
            skill.setDefaultVersion(version);
            skillMapper.updateById(skill);
        }
        log.info("skill version uploaded: tenant={}, skill={}, version={}, sha256={}, publish={}",
                caller.tenantId(), skillCode, version, sha256, publish);
        return new SkillDetail.VersionView(version, sha256, (long) zipBytes.length,
                skillVersion.getStatus(), changelog, skillVersion.getCreatedAt() == null ? null
                        : skillVersion.getCreatedAt().toString(), ossKey, ossUrl);
    }

    /**
     * 包结构校验 + 类型识别（§7 沙箱安全前置）：
     * 根入口 main.py → CODE（沙箱 python main.py，§4.7）；
     * SKILL.md → AGENT（指令型技能，沙箱经平台 agent runner 执行，包零改造）。
     */
    private String detectKind(byte[] zipBytes) {
        boolean hasMainEntry = false;
        boolean hasSkillMd = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            int entries = 0;
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                String name = entry.getName();
                if (name.startsWith("/") || name.contains("..") || name.contains("\\")) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "包内路径非法（禁止绝对路径/穿越）: " + name);
                }
                if ("main.py".equals(name)) {
                    hasMainEntry = true;
                }
                if ("SKILL.md".equals(name)) {
                    hasSkillMd = true;
                }
                if (entries > 20000) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "包内文件数过多");
                }
            }
        } catch (IOException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "zip 包损坏或无法解析");
        }
        if (hasMainEntry) {
            return Skill.KIND_CODE;
        }
        if (hasSkillMd) {
            return Skill.KIND_AGENT;
        }
        throw new BizException(ErrorCode.PARAM_INVALID,
                "包缺少入口：需 main.py（代码技能，python main.py）或 SKILL.md（智能体技能，平台 agent runner 执行）");
    }

    /**
     * 单顶层目录自动剥壳（右键压缩文件夹的常见形态 X/... → ...）：
     * 无根入口且所有条目共享唯一顶层目录、且该目录下存在入口时，重写 zip 展平。
     * 顺带过滤 macOS 压缩附带项（__MACOSX/、.DS_Store），保证沙箱工作区根即入口。
     */
    private byte[] flattenSingleRootDir(byte[] zipBytes) {
        record Scan(boolean hasRootEntry, String singleTop, boolean entryUnderTop) {}
        java.util.Set<String> tops = new java.util.HashSet<>();
        boolean hasRootEntry = false;
        boolean entryUnderTop = false;
        String singleTop = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith("/") || name.startsWith("__MACOSX/") || name.contains("/.DS_Store")
                        || name.equals(".DS_Store")) {
                    continue;
                }
                int slash = name.indexOf('/');
                if (slash < 0) {
                    if ("main.py".equals(name) || "SKILL.md".equals(name)) {
                        hasRootEntry = true;
                    }
                } else {
                    String top = name.substring(0, slash);
                    if (!top.equals("__MACOSX")) {
                        tops.add(top);
                        if ("main.py".equals(name.substring(slash + 1))
                                || "SKILL.md".equals(name.substring(slash + 1))) {
                            entryUnderTop = true;
                        }
                    }
                }
            }
        } catch (IOException e) {
            return zipBytes;
        }
        if (hasRootEntry || tops.size() != 1 || !entryUnderTop) {
            return zipBytes;
        }
        singleTop = tops.iterator().next() + "/";
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes));
             ZipOutputStream out = new ZipOutputStream(buffer)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith("/") || name.startsWith("__MACOSX/") || name.equals(".DS_Store")
                        || name.contains("/.DS_Store")) {
                    continue;
                }
                String stripped = name.startsWith(singleTop) ? name.substring(singleTop.length()) : name;
                if (stripped.isBlank()) {
                    continue;
                }
                out.putNextEntry(new ZipEntry(stripped));
                zip.transferTo(out);
                out.closeEntry();
            }
        } catch (IOException e) {
            return zipBytes;
        }
        log.info("skill 包单顶层目录已自动剥壳: {}", singleTop);
        return buffer.toByteArray();
    }

    // ------------------------------------------------------------------
    // 查询 / 配置 / 版本
    // ------------------------------------------------------------------

    public SkillPage list(CallerContext caller, int pageNo, int pageSize) {
        Page<Skill> page = skillMapper.selectPage(new Page<>(Math.max(pageNo, 1), clampSize(pageSize)),
                new LambdaQueryWrapper<Skill>()
                        .eq(Skill::getTenantId, caller.tenantId())
                        .orderByDesc(Skill::getId));
        return new SkillPage(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(SkillService::toSummary).toList());
    }

    public SkillDetail detail(CallerContext caller, String skillCode) {
        Skill skill = ownedSkill(caller, skillCode);
        List<SkillDetail.VersionView> versions = skillVersionMapper.selectList(
                        new LambdaQueryWrapper<SkillVersion>()
                                .eq(SkillVersion::getSkillId, skill.getId())
                                .orderByDesc(SkillVersion::getId))
                .stream()
                .map(v -> new SkillDetail.VersionView(v.getVersion(), v.getPackageSha256(),
                        v.getPackageSize(), v.getStatus(), v.getChangelog(),
                        v.getCreatedAt() == null ? null : v.getCreatedAt().toString(),
                        v.getOssKey(), v.getOssUrl()))
                .toList();
        return new SkillDetail(toSummary(skill), versions);
    }

    /** 运营配置即时生效，无需重传包（§4.1）；下次执行立即读到新值 */
    public SkillSummary updateConfig(CallerContext caller, String skillCode,
                                     SkillDtos.ConfigUpdateRequest request) {
        Skill skill = ownedSkill(caller, skillCode);
        if (request.outputConfig() != null) {
            if (!JsonCodec.isValidJson(request.outputConfig())) {
                throw new BizException(ErrorCode.PARAM_INVALID, "outputConfig 必须为合法 JSON");
            }
            skill.setOutputConfig(request.outputConfig());
        }
        if (request.pricingConfig() != null) {
            if (!JsonCodec.isValidJson(request.pricingConfig())) {
                throw new BizException(ErrorCode.PARAM_INVALID, "pricingConfig 必须为合法 JSON");
            }
            skill.setPricingConfig(request.pricingConfig());
            syncTelemetryToken(skill);
        }
        skillMapper.updateById(skill);
        return toSummary(skill);
    }

    /**
     * 删除 Skill（§10.1）：本租户硬删元数据与版本记录，并清理 COS 中的包对象；
     * 历史执行记录（execution/artifact）保留审计，产物预签名 URL 独立于包对象不受影响。
     */
    @Transactional
    public void delete(CallerContext caller, String skillCode) {
        Skill skill = ownedSkill(caller, skillCode);
        List<SkillVersion> versions = skillVersionMapper.selectList(
                new LambdaQueryWrapper<SkillVersion>().eq(SkillVersion::getSkillId, skill.getId()));
        for (SkillVersion version : versions) {
            try {
                objectStorage.delete(version.getOssKey());
            } catch (Exception e) {
                log.warn("删除包对象失败（继续删记录）: {}", version.getOssKey());
            }
        }
        skillVersionMapper.delete(new LambdaQueryWrapper<SkillVersion>()
                .eq(SkillVersion::getSkillId, skill.getId()));
        skillMapper.deleteById(skill.getId());
        log.info("skill deleted: tenant={}, skill={}, versions={}",
                caller.tenantId(), skillCode, versions.size());
    }

    /**
     * 版本审核状态流转（§10.1 审核）：0=待审核 →1 审核通过发布 / →2 驳回废弃；
     * 1→2 下架废弃。废弃为终态（包永不覆盖，重新上传新版本即可）。
     */
    public SkillDetail.VersionView reviewVersion(CallerContext caller, String skillCode,
                                                 String version, int targetStatus) {
        Skill skill = ownedSkill(caller, skillCode);
        SkillVersion skillVersion = skillVersionMapper.selectOne(new LambdaQueryWrapper<SkillVersion>()
                .eq(SkillVersion::getSkillId, skill.getId())
                .eq(SkillVersion::getVersion, version)
                .last("LIMIT 1"));
        if (skillVersion == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "版本不存在: " + version);
        }
        if (targetStatus != SkillVersion.STATUS_PUBLISHED
                && targetStatus != SkillVersion.STATUS_DEPRECATED) {
            throw new BizException(ErrorCode.PARAM_INVALID, "目标状态仅支持 1=发布 / 2=废弃");
        }
        int current = skillVersion.getStatus();
        boolean allowed = (current == SkillVersion.STATUS_UPLOADED)
                || (current == SkillVersion.STATUS_PUBLISHED && targetStatus == SkillVersion.STATUS_DEPRECATED);
        if (!allowed) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "状态不允许流转: " + current + " → " + targetStatus + "（废弃为终态）");
        }
        skillVersion.setStatus(targetStatus);
        skillVersionMapper.updateById(skillVersion);
        if (targetStatus == SkillVersion.STATUS_PUBLISHED
                && (skill.getDefaultVersion() == null || skill.getDefaultVersion().isBlank())) {
            skill.setDefaultVersion(version);
            skillMapper.updateById(skill);
        }
        log.info("skill version reviewed: tenant={}, skill={}, version={}, {} -> {}",
                caller.tenantId(), skillCode, version, current, targetStatus);
        return new SkillDetail.VersionView(version, skillVersion.getPackageSha256(),
                skillVersion.getPackageSize(), targetStatus, skillVersion.getChangelog(),
                skillVersion.getCreatedAt() == null ? null : skillVersion.getCreatedAt().toString(),
                skillVersion.getOssKey(), skillVersion.getOssUrl());
    }

    /**
     * 市场上下架（§10.1 市场管理）：切可见性；上架 PUBLIC 需存在已发布版本。
     */
    public SkillSummary setVisibility(CallerContext caller, String skillCode, String visibility) {
        if (!Skill.VISIBILITY_PUBLIC.equals(visibility) && !Skill.VISIBILITY_PRIVATE.equals(visibility)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "visibility 仅支持 PUBLIC / PRIVATE");
        }
        Skill skill = ownedSkill(caller, skillCode);
        if (Skill.VISIBILITY_PUBLIC.equals(visibility)) {
            Long published = skillVersionMapper.selectCount(new LambdaQueryWrapper<SkillVersion>()
                    .eq(SkillVersion::getSkillId, skill.getId())
                    .eq(SkillVersion::getStatus, SkillVersion.STATUS_PUBLISHED));
            if (published == null || published == 0) {
                throw new BizException(ErrorCode.PARAM_INVALID, "上架 PUBLIC 需先有已发布版本");
            }
        }
        skill.setVisibility(visibility);
        skillMapper.updateById(skill);
        return toSummary(skill);
    }

    public SkillSummary setDefaultVersion(CallerContext caller, String skillCode,
                                          DefaultVersionRequest request) {
        Skill skill = ownedSkill(caller, skillCode);
        SkillVersion version = skillVersionMapper.selectOne(new LambdaQueryWrapper<SkillVersion>()
                .eq(SkillVersion::getSkillId, skill.getId())
                .eq(SkillVersion::getVersion, request.version())
                .last("LIMIT 1"));
        if (version == null || version.getStatus() != SkillVersion.STATUS_PUBLISHED) {
            throw new BizException(ErrorCode.PARAM_INVALID, "版本不存在或未发布: " + request.version());
        }
        // 秒级回滚 = 改标记（§8.3）
        skill.setDefaultVersion(request.version());
        skillMapper.updateById(skill);
        return toSummary(skill);
    }

    // ------------------------------------------------------------------
    // 分发包下载与市场
    // ------------------------------------------------------------------

    /** 下载分发包（FREE 公开分发）：PUBLIC + 已发布版本才放行；独立留痕统计分发量（TC-FRE-002） */
    public PackageDownloadView downloadPackage(CallerContext caller, String skillCode, String version) {
        Skill skill = skillMapper.selectOne(new LambdaQueryWrapper<Skill>()
                .eq(Skill::getSkillCode, skillCode)
                .eq(Skill::getVisibility, Skill.VISIBILITY_PUBLIC)
                .eq(Skill::getStatus, 1)
                .last("LIMIT 1"));
        if (skill == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "分发包仅对 PUBLIC Skill 开放");
        }
        String target = version == null || version.isBlank() ? skill.getDefaultVersion() : version;
        SkillVersion skillVersion = skillVersionMapper.selectOne(new LambdaQueryWrapper<SkillVersion>()
                .eq(SkillVersion::getSkillId, skill.getId())
                .eq(SkillVersion::getVersion, target)
                .last("LIMIT 1"));
        if (skillVersion == null || skillVersion.getStatus() != SkillVersion.STATUS_PUBLISHED) {
            throw new BizException(ErrorCode.PARAM_INVALID, "版本不存在或未通过审核: " + target);
        }
        rateLimitService.countPackageDownload(skillCode);
        ObjectStorage.PresignedUrl url = objectStorage.presignGet(skillVersion.getOssKey(), PACKAGE_URL_TTL);
        return new PackageDownloadView(skillVersion.getVersion(), skillVersion.getPackageSha256(),
                url.url(), url.expiresAt().toString());
    }

    public MarketplacePage marketplace(int pageNo, int pageSize) {
        Page<Skill> page = skillMapper.selectPage(new Page<>(Math.max(pageNo, 1), clampSize(pageSize)),
                new LambdaQueryWrapper<Skill>()
                        .eq(Skill::getVisibility, Skill.VISIBILITY_PUBLIC)
                        .eq(Skill::getStatus, 1)
                        .isNotNull(Skill::getDefaultVersion)
                        .orderByDesc(Skill::getId));
        return new MarketplacePage(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream()
                        .map(s -> new MarketplaceItem(s.getSkillCode(), s.getName(), s.getDescription(),
                                s.getDefaultVersion(), s.getPricingConfig(), s.getInvocationSpec()))
                        .toList());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Skill ownedSkill(CallerContext caller, String skillCode) {
        Skill skill = skillMapper.selectOne(new LambdaQueryWrapper<Skill>()
                .eq(Skill::getTenantId, caller.tenantId())
                .eq(Skill::getSkillCode, skillCode)
                .last("LIMIT 1"));
        if (skill == null) {
            throw new BizException(ErrorCode.SKILL_NOT_FOUND, "Skill 不存在: " + skillCode);
        }
        return skill;
    }

    /** FREE 公开分发模式自动签发埋点令牌（§5.7 telemetry） */
    private void syncTelemetryToken(Skill skill) {
        boolean free = "FREE".equals(JsonCodec.treeOrEmpty(skill.getPricingConfig())
                .path("mode").asText("PER_EXECUTION"));
        if (free && (skill.getTelemetryToken() == null || skill.getTelemetryToken().isBlank())) {
            skill.setTelemetryToken(Ids.newTelemetryToken());
        }
    }

    private static SkillSummary toSummary(Skill skill) {
        return new SkillSummary(skill.getSkillCode(), skill.getName(), skill.getDescription(),
                skill.getVisibility(), skill.getKind(), skill.getDefaultVersion(), skill.getStatus(),
                skill.getPricingConfig());
    }

    private static int clampSize(int pageSize) {
        return Math.min(Math.max(pageSize, 1), 100);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("sha256 failed", e);
        }
    }
}

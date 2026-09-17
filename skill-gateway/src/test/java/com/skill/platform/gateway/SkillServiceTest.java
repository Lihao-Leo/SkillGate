package com.skill.platform.gateway;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.common.ErrorCode;
import com.skill.platform.gateway.dal.entity.Skill;
import com.skill.platform.gateway.dal.entity.SkillVersion;
import com.skill.platform.gateway.dal.mapper.SkillMapper;
import com.skill.platform.gateway.dal.mapper.SkillVersionMapper;
import com.skill.platform.gateway.security.CallerContext;
import com.skill.platform.gateway.service.SkillDtos;
import com.skill.platform.gateway.service.SkillService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Skill 生命周期测试（§4.1 / §5.2）：上传校验、版本不覆盖、配置即时生效、
 * 分发包门禁（TC-FRE-002）、市场列表。
 */
@SpringBootTest
@ActiveProfiles("local")
class SkillServiceTest {

    @Autowired
    private SkillService skillService;
    @Autowired
    private SkillMapper skillMapper;
    @Autowired
    private SkillVersionMapper skillVersionMapper;

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private byte[] zip(String... entries) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (int i = 0; i < entries.length; i += 2) {
                zip.putNextEntry(new ZipEntry(entries[i]));
                zip.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    private CallerContext caller(String tenantId) {
        return new CallerContext("sk-svc-" + uniq(), tenantId);
    }

    @Test
    void upload_publishes_version_and_records_sha256() throws Exception {
        CallerContext caller = caller("tenant-" + uniq());
        String skillCode = "demo-" + uniq();
        byte[] pkg = zip("main.py", "print('hi')", "prompts/tpl.md", "# tpl",
                "requirements.txt", "requests");

        SkillDtos.SkillDetail.VersionView view = skillService.upload(caller, skillCode, "1.0.0",
                "演示 Skill", "描述", null, "[\"llm-text\"]",
                "{\"countable\":true,\"defaultCount\":1,\"maxCount\":10,\"timeoutSeconds\":600}",
                "{\"mode\":\"PER_EXECUTION\",\"points\":10}", null, "init", true, pkg);

        assertThat(view.status()).isEqualTo(SkillVersion.STATUS_PUBLISHED);
        assertThat(view.packageSha256()).hasSize(64);
        assertThat(view.packageSize()).isEqualTo(pkg.length);
        assertThat(view.ossKey()).contains("/skill.zip");
        assertThat(view.packageUrl()).startsWith("https://oss.local/").endsWith(".zip");

        Skill skill = skillMapper.selectOne(new LambdaQueryWrapper<Skill>()
                .eq(Skill::getSkillCode, skillCode).last("LIMIT 1"));
        assertThat(skill.getDefaultVersion()).isEqualTo("1.0.0");
        SkillVersion version = skillVersionMapper.selectOne(new LambdaQueryWrapper<SkillVersion>()
                .eq(SkillVersion::getSkillId, skill.getId()).eq(SkillVersion::getVersion, "1.0.0"));
        assertThat(version.getOssKey()).isEqualTo(
                "skill/%s/%s/1.0.0/skill.zip".formatted(caller.tenantId(), skillCode));
    }

    @Test
    void upload_skillmd_package_detected_as_agent_kind() throws Exception {
        CallerContext caller = caller("tenant-" + uniq());
        String skillCode = "agent-" + uniq();

        SkillDtos.SkillDetail.VersionView view = skillService.upload(caller, skillCode, "1.0.0",
                "智能体技能", null, null, null, null, null, null, null, true,
                zip("SKILL.md", "---\nname: demo\n---\n# demo",
                        "references/schema.md", "# schema",
                        "scripts/render.py", "print('x')"));

        Skill skill = skillMapper.selectOne(new LambdaQueryWrapper<Skill>()
                .eq(Skill::getSkillCode, skillCode).last("LIMIT 1"));
        assertThat(skill.getKind()).isEqualTo(Skill.KIND_AGENT);
        assertThat(view.ossKey()).contains(skillCode);
    }

    @Test
    void upload_flattens_single_root_wrapper_dir() throws Exception {
        CallerContext caller = caller("tenant-" + uniq());
        String skillCode = "wrapped-" + uniq();

        // 右键压缩文件夹的常见形态：所有条目都在唯一顶层目录下（含 macOS 垃圾项）
        SkillDtos.SkillDetail.VersionView view = skillService.upload(caller, skillCode, "1.0.0",
                "智能体技能", null, null, null, null, null, null, null, true,
                zip(skillCode + "/SKILL.md", "---\nname: demo\n---\n# demo",
                        skillCode + "/references/schema.md", "# schema",
                        "__MACOSX/._skillCode", "junk",
                        skillCode + "/.DS_Store", "junk"));

        Skill skill = skillMapper.selectOne(new LambdaQueryWrapper<Skill>()
                .eq(Skill::getSkillCode, skillCode).last("LIMIT 1"));
        assertThat(skill.getKind()).isEqualTo(Skill.KIND_AGENT);
        assertThat(view.ossKey()).contains(skillCode);
    }

    @Test
    void upload_rejects_package_without_any_entry() throws Exception {
        CallerContext caller = caller("tenant-" + uniq());
        assertThatThrownBy(() -> skillService.upload(caller, "none-" + uniq(), "1.0.0",
                null, null, null, null, null, null, null, null, false,
                zip("README.md", "no entry")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("包缺少入口");
    }

    @Test
    void upload_rejects_kind_mismatch_for_same_skill_code() throws Exception {
        CallerContext caller = caller("tenant-" + uniq());
        String skillCode = "mixed-" + uniq();
        skillService.upload(caller, skillCode, "1.0.0", null, null, null, null,
                null, null, null, null, false, zip("main.py", "x"));
        assertThatThrownBy(() -> skillService.upload(caller, skillCode, "1.1.0",
                null, null, null, null, null, null, null, null, false,
                zip("SKILL.md", "# agent")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("混型");
    }

    @Test
    void upload_without_publish_leaves_version_pending_and_no_default() throws Exception {
        CallerContext caller = caller("tenant-" + uniq());
        String skillCode = "draft-" + uniq();

        skillService.upload(caller, skillCode, "0.9.0", null, null, null, null, null,
                "{\"mode\":\"PER_EXECUTION\",\"points\":5}", null, null, false,
                zip("main.py", "x"));

        Skill skill = skillMapper.selectOne(new LambdaQueryWrapper<Skill>()
                .eq(Skill::getSkillCode, skillCode).last("LIMIT 1"));
        assertThat(skill.getDefaultVersion()).isNull();
        SkillVersion version = skillVersionMapper.selectOne(new LambdaQueryWrapper<SkillVersion>()
                .eq(SkillVersion::getSkillId, skill.getId()));
        assertThat(version.getStatus()).isEqualTo(SkillVersion.STATUS_UPLOADED);
    }

    @Test
    void duplicate_version_rejected_version_never_overwritten() throws Exception {
        CallerContext caller = caller("tenant-" + uniq());
        String skillCode = "ver-" + uniq();
        skillService.upload(caller, skillCode, "1.0.0", null, null, null, null, null,
                "{\"mode\":\"PER_EXECUTION\",\"points\":5}", null, null, true, zip("main.py", "a"));

        assertThatThrownBy(() -> skillService.upload(caller, skillCode, "1.0.0", null, null, null,
                null, null, null, null, null, true, zip("main.py", "b")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.PARAM_INVALID));

        // 新版本新路径（不覆盖）
        skillService.upload(caller, skillCode, "1.1.0", null, null, null, null, null, null,
                null, null, true, zip("main.py", "c"));
        assertThat(skillVersionMapper.selectCount(new LambdaQueryWrapper<SkillVersion>()
                .eq(SkillVersion::getSkillId,
                        skillMapper.selectOne(new LambdaQueryWrapper<Skill>()
                                .eq(Skill::getSkillCode, skillCode).last("LIMIT 1")).getId())))
                .isEqualTo(2);
    }

    @Test
    void path_traversal_and_missing_entry_rejected() {
        CallerContext caller = caller("tenant-" + uniq());
        assertThatThrownBy(() -> skillService.upload(caller, "evil-" + uniq(), "1.0.0", null, null,
                null, null, null, "{\"mode\":\"FREE\"}", null, null, true,
                zip("../escape.py", "x", "main.py", "y")))
                .isInstanceOf(BizException.class);

        assertThatThrownBy(() -> skillService.upload(caller, "nomain-" + uniq(), "1.0.0", null, null,
                null, null, null, "{\"mode\":\"FREE\"}", null, null, true,
                zip("utils.py", "x")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains("main.py"));
    }

    @Test
    void config_update_takes_effect_immediately() throws Exception {
        CallerContext caller = caller("tenant-" + uniq());
        String skillCode = "cfg-" + uniq();
        skillService.upload(caller, skillCode, "1.0.0", null, null, null, null,
                "{\"countable\":true,\"defaultCount\":1,\"maxCount\":5}",
                "{\"mode\":\"PER_EXECUTION\",\"points\":10}", null, null, true, zip("main.py", "x"));

        SkillDtos.SkillSummary updated = skillService.updateConfig(caller, skillCode,
                new SkillDtos.ConfigUpdateRequest("{\"countable\":true,\"defaultCount\":2,\"maxCount\":30}",
                        "{\"mode\":\"PER_EXECUTION\",\"points\":12}"));

        assertThat(updated.defaultVersion()).isEqualTo("1.0.0");
        Skill skill = skillMapper.selectOne(new LambdaQueryWrapper<Skill>()
                .eq(Skill::getSkillCode, skillCode).last("LIMIT 1"));
        assertThat(skill.getOutputConfig()).contains("\"maxCount\":30");
        assertThat(skill.getPricingConfig()).contains("\"points\":12");
    }

    @Test
    void default_version_switch_is_second_level_rollback() throws Exception {
        CallerContext caller = caller("tenant-" + uniq());
        String skillCode = "roll-" + uniq();
        skillService.upload(caller, skillCode, "1.0.0", null, null, null, null, null,
                "{\"mode\":\"PER_EXECUTION\",\"points\":10}", null, null, true, zip("main.py", "a"));
        skillService.upload(caller, skillCode, "1.1.0", null, null, null, null, null,
                "{\"mode\":\"PER_EXECUTION\",\"points\":10}", null, null, true, zip("main.py", "b"));

        skillService.setDefaultVersion(caller, skillCode, new SkillDtos.DefaultVersionRequest("1.0.0"));

        Skill skill = skillMapper.selectOne(new LambdaQueryWrapper<Skill>()
                .eq(Skill::getSkillCode, skillCode).last("LIMIT 1"));
        assertThat(skill.getDefaultVersion()).isEqualTo("1.0.0");

        // 未发布版本不可设为默认
        skillService.upload(caller, skillCode, "2.0.0-rc1", null, null, null, null, null, null,
                null, null, false, zip("main.py", "c"));
        assertThatThrownBy(() -> skillService.setDefaultVersion(caller, skillCode,
                new SkillDtos.DefaultVersionRequest("2.0.0-rc1")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void package_download_gated_by_public_and_published() throws Exception {
        CallerContext owner = caller("tenant-" + uniq());
        String privateCode = "priv-" + uniq();
        skillService.upload(owner, privateCode, "1.0.0", null, null, "PRIVATE", null, null,
                "{\"mode\":\"FREE\"}", null, null, true, zip("main.py", "x"));

        // PRIVATE 拒绝（即使 FREE）
        assertThatThrownBy(() -> skillService.downloadPackage(owner, privateCode, null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        // PUBLIC + 已发布放行（任意有效 AppKey）
        String publicCode = "pub-" + uniq();
        skillService.upload(owner, publicCode, "1.0.0", null, null, "PUBLIC", null, null,
                "{\"mode\":\"FREE\"}", null, null, true, zip("main.py", "x"));
        CallerContext otherCaller = caller("tenant-other-" + uniq());
        SkillDtos.PackageDownloadView view = skillService.downloadPackage(otherCaller, publicCode, null);

        assertThat(view.downloadUrl()).contains("presign=get");
        assertThat(view.packageSha256()).hasSize(64);
    }

    @Test
    void free_pricing_auto_issues_telemetry_token() throws Exception {
        CallerContext caller = caller("tenant-" + uniq());
        String skillCode = "free-" + uniq();
        skillService.upload(caller, skillCode, "1.0.0", null, null, "PUBLIC", null, null,
                "{\"mode\":\"FREE\"}", null, null, true, zip("main.py", "x"));

        Skill skill = skillMapper.selectOne(new LambdaQueryWrapper<Skill>()
                .eq(Skill::getSkillCode, skillCode).last("LIMIT 1"));
        assertThat(skill.getTelemetryToken()).startsWith("skt-");
    }

    @Test
    void marketplace_lists_public_published_only() throws Exception {
        CallerContext caller = caller("tenant-" + uniq());
        String publicCode = "market-" + uniq();
        skillService.upload(caller, publicCode, "1.0.0", "市场 Skill", "公开说明", "PUBLIC", null,
                null, "{\"mode\":\"PER_EXECUTION\",\"points\":9}", null, null, true, zip("main.py", "x"));

        SkillDtos.MarketplacePage page = skillService.marketplace(1, 100);
        assertThat(page.items()).anyMatch(item -> item.skillCode().equals(publicCode));
        assertThat(page.items()).allMatch(item -> item.defaultVersion() != null);
    }

    @Test
    void review_version_transitions_and_visibility_gates() throws Exception {
        CallerContext caller = caller("tenant-" + uniq());
        String skillCode = "review-" + uniq();

        // 待审核（不直接发布）
        skillService.upload(caller, skillCode, "1.0.0", null, null, null, null,
                null, null, null, null, false, zip("main.py", "x"));

        // 未发布前不能上架 PUBLIC
        assertThatThrownBy(() -> skillService.setVisibility(caller, skillCode, Skill.VISIBILITY_PUBLIC))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已发布版本");

        // 审核通过 → 发布；首个发布版本自动成为默认版本
        SkillDtos.SkillDetail.VersionView published = skillService.reviewVersion(
                caller, skillCode, "1.0.0", SkillVersion.STATUS_PUBLISHED);
        assertThat(published.status()).isEqualTo(SkillVersion.STATUS_PUBLISHED);
        Skill skill = skillMapper.selectOne(new LambdaQueryWrapper<Skill>()
                .eq(Skill::getSkillCode, skillCode).last("LIMIT 1"));
        assertThat(skill.getDefaultVersion()).isEqualTo("1.0.0");

        // 非法目标状态被拒绝
        assertThatThrownBy(() -> skillService.reviewVersion(caller, skillCode, "1.0.0",
                SkillVersion.STATUS_UPLOADED))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("目标状态仅支持");

        // 上架 PUBLIC（已有发布版本）→ 下架
        assertThat(skillService.setVisibility(caller, skillCode, Skill.VISIBILITY_PUBLIC)
                .visibility()).isEqualTo("PUBLIC");

        // 下架废弃为终态
        SkillDtos.SkillDetail.VersionView deprecated = skillService.reviewVersion(
                caller, skillCode, "1.0.0", SkillVersion.STATUS_DEPRECATED);
        assertThat(deprecated.status()).isEqualTo(SkillVersion.STATUS_DEPRECATED);
        assertThatThrownBy(() -> skillService.reviewVersion(caller, skillCode, "1.0.0",
                SkillVersion.STATUS_PUBLISHED))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("状态不允许流转");
    }

}

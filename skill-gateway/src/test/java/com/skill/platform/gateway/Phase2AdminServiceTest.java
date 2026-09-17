package com.skill.platform.gateway;

import com.skill.platform.gateway.dal.entity.ModelProvider;
import com.skill.platform.gateway.dal.mapper.KbDocumentMapper;
import com.skill.platform.gateway.dal.mapper.KbMapper;
import com.skill.platform.gateway.dal.mapper.ModelProviderMapper;
import com.skill.platform.gateway.security.CallerContext;
import com.skill.platform.gateway.service.KbAdminService;
import com.skill.platform.gateway.service.MetricsService;
import com.skill.platform.gateway.service.ModelProviderAdminService;
import com.skill.platform.gateway.service.SysConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 二期管理面服务测试：系统配置覆盖、看板指标、模型供应商（key 不回显）、KB 管理闭环。
 */
@SpringBootTest
@ActiveProfiles("local")
class Phase2AdminServiceTest {

    @Autowired
    private SysConfigService sysConfigService;
    @Autowired
    private MetricsService metricsService;
    @Autowired
    private ModelProviderAdminService modelProviderAdminService;
    @Autowired
    private KbAdminService kbAdminService;
    @Autowired
    private KbMapper kbMapper;
    @Autowired
    private KbDocumentMapper kbDocumentMapper;
    @Autowired
    private ModelProviderMapper modelProviderMapper;

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void sys_config_overrides_defaults() {
        String key = "quota.qps";
        int original = sysConfigService.intOf(key, 10);
        sysConfigService.put(key, "77", "测试");
        assertThat(sysConfigService.intOf(key, 10)).isEqualTo(77);
        sysConfigService.put(key, String.valueOf(original), "还原");
        assertThat(sysConfigService.intOf(key, 10)).isEqualTo(original);
    }

    @Test
    void metrics_overview_returns_core_fields() {
        Map<String, Object> metrics = metricsService.overview();
        assertThat(metrics).containsKeys("pending", "running", "succeededToday",
                "failedToday", "pendingAgeP95Minutes", "insufficientPointsToday");
    }

    @Test
    void model_provider_upsert_hides_api_key() {
        String alias = "llm-test-" + uniq();
        Map<String, Object> created = modelProviderAdminService.upsert(alias, "openai",
                "gpt-4o-mini", "https://api.openai.com", "sk-secret-key", null, 5,
                "0.01", "0.02", null, 1);
        assertThat(created.get("hasApiKey")).isEqualTo(true);
        assertThat(created.toString()).doesNotContain("sk-secret-key");

        // 更新不传 apiKey：保留原密钥
        Map<String, Object> updated = modelProviderAdminService.upsert(alias, "openai",
                "gpt-4o", "https://api.openai.com", null, null, 8, null, null, null, 1);
        assertThat(updated.get("modelName")).isEqualTo("gpt-4o");
        assertThat(updated.get("hasApiKey")).isEqualTo(true);

        modelProviderMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModelProvider>()
                .eq(ModelProvider::getAlias, alias));
    }

    @Test
    void kb_lifecycle_with_ingest_disabled_semantics() {
        String tenant = "tenant-" + uniq();
        Map<String, Object> kb = kbAdminService.createKb(tenant, "运营知识库", "embedding-default");
        String kbId = (String) kb.get("kbId");
        assertThat((String) kb.get("milvusCollection")).isEqualTo(tenant + "__" + kbId);

        // 每段 ~900 字符 → 3 个独立分块（~800 字符合并阈值）
        String paragraph = "内容".repeat(450) + "。";
        String text = paragraph + "\n\n" + paragraph + "\n\n" + paragraph;
        Map<String, Object> document = kbAdminService.addDocument(kbId, "notes.txt",
                text.getBytes(StandardCharsets.UTF_8));
        String docId = (String) document.get("docId");
        assertThat(document.get("status")).isEqualTo(0);

        Map<String, Object> ingest = kbAdminService.ingest(kbId, docId);
        assertThat(ingest.get("chunkCount")).isEqualTo(3);
        assertThat(ingest.get("indexed")).isEqualTo(false);
        assertThat((String) ingest.get("message")).contains("Milvus");

        kbAdminService.deleteDocument(kbId, docId);
        assertThat(kbDocumentMapper.selectList(null).stream()
                .noneMatch(d -> docId.equals(d.getDocId()))).isTrue();
        kbAdminService.deleteKb(kbId);
        assertThat(kbMapper.selectList(null).stream()
                .noneMatch(k -> kbId.equals(k.getKbId()))).isTrue();
    }
}

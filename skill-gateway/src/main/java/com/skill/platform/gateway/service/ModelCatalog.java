package com.skill.platform.gateway.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 模型目录（V2.1 静态预设）：前端"多模型下拉框配置"数据源——选供应商带出 endpoint 模板与
 * 常见模型列表；静态数据随版本演进，不必查库（实际可用模型以供应商控制台为准）。
 */
@Service
public class ModelCatalog {

    /** 单供应商目录项。 */
    public record CatalogEntry(String provider, String label, String endpointHint,
                               List<String> models) {
    }

    private static final List<CatalogEntry> CATALOG = List.of(
            new CatalogEntry("openai", "OpenAI", "https://api.openai.com/v1",
                    List.of("gpt-5.1", "gpt-5-mini", "gpt-4.1", "gpt-4o-mini")),
            new CatalogEntry("anthropic", "Anthropic", "https://api.anthropic.com/v1",
                    List.of("claude-sonnet-5", "claude-opus-5", "claude-haiku-4-5-20251001")),
            new CatalogEntry("deepseek", "DeepSeek", "https://api.deepseek.com/v1",
                    List.of("deepseek-chat", "deepseek-reasoner")),
            new CatalogEntry("qwen", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    List.of("qwen-max", "qwen-plus", "qwen-turbo", "qwen3-235b-a22b")),
            new CatalogEntry("glm", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4",
                    List.of("glm-5.3-flash", "glm-5-plus", "glm-5-air")),
            new CatalogEntry("moonshot", "Moonshot", "https://api.moonshot.cn/v1",
                    List.of("kimi-k2", "moonshot-v1-128k")),
            new CatalogEntry("doubao", "豆包", "https://ark.cn-beijing.volces.com/api/v3",
                    List.of("doubao-seed-1-8", "doubao-1-5-pro-32k")),
            new CatalogEntry("ollama", "Ollama（本地）", "http://127.0.0.1:11434/v1",
                    List.of("qwen3:14b", "llama3.1:8b", "deepseek-r1:14b")),
            new CatalogEntry("custom", "自定义（OpenAI 兼容）", "https://your-endpoint.example.com/v1",
                    List.of())
    );

    /** 目录（前端 el-select 数据源）。 */
    public List<Map<String, Object>> catalog() {
        return CATALOG.stream().map(entry -> {
            Map<String, Object> view = new java.util.LinkedHashMap<String, Object>();
            view.put("provider", entry.provider());
            view.put("label", entry.label());
            view.put("endpointHint", entry.endpointHint());
            view.put("models", entry.models());
            return (Map<String, Object>) view;
        }).toList();
    }
}

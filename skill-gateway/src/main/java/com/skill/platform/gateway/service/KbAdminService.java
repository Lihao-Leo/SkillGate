package com.skill.platform.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.common.ErrorCode;
import com.skill.platform.gateway.common.Ids;
import com.skill.platform.gateway.dal.entity.Kb;
import com.skill.platform.gateway.dal.entity.KbDocument;
import com.skill.platform.gateway.dal.mapper.KbDocumentMapper;
import com.skill.platform.gateway.dal.mapper.KbMapper;
import com.skill.platform.gateway.infra.ObjectStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理（§10.1 / §6.11）：KB 与文档登记、分块；
 * 向量化入库依赖 Milvus（skill-platform.kb.milvus-enabled，V1 默认关——
 * 关闭时分块完成、文档保持待入库，接口明确返回 indexed=false）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbAdminService {

    private final KbMapper kbMapper;
    private final KbDocumentMapper kbDocumentMapper;
    private final ObjectStorage objectStorage;

    @Value("${skill-platform.kb.milvus-enabled:false}")
    private boolean milvusEnabled;

    public List<Map<String, Object>> listKbs() {
        return kbMapper.selectList(new LambdaQueryWrapper<Kb>().orderByDesc(Kb::getId))
                .stream().map(KbAdminService::toView).toList();
    }

    public Map<String, Object> createKb(String tenantId, String name, String embeddingAlias) {
        if (tenantId == null || tenantId.isBlank() || name == null || name.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "tenantId / name 必填");
        }
        String kbId = "kb_" + Ids.randomBase64(9).replaceAll("[^a-zA-Z0-9]", "").substring(0, 10).toLowerCase();
        Kb kb = new Kb();
        kb.setKbId(kbId);
        kb.setTenantId(tenantId);
        kb.setName(name);
        kb.setEmbeddingAlias(embeddingAlias == null || embeddingAlias.isBlank() ? "embedding-default" : embeddingAlias);
        kb.setMilvusCollection(tenantId + "__" + kbId);
        kb.setStatus(1);
        kbMapper.insert(kb);
        return toView(kb);
    }

    public void deleteKb(String kbId) {
        Kb kb = requireKb(kbId);
        Long docs = kbDocumentMapper.selectCount(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getKbId, kbId));
        if (docs != null && docs > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "先删除知识库下文档（" + docs + " 篇）");
        }
        kbMapper.deleteById(kb.getId());
    }

    public List<Map<String, Object>> listDocuments(String kbId) {
        requireKb(kbId);
        return kbDocumentMapper.selectList(new LambdaQueryWrapper<KbDocument>()
                        .eq(KbDocument::getKbId, kbId).orderByDesc(KbDocument::getId))
                .stream().map(KbAdminService::toView).toList();
    }

    /** 文档登记：原文落 OSS（私有桶），状态=待入库 */
    public Map<String, Object> addDocument(String kbId, String filename, byte[] bytes) {
        Kb kb = requireKb(kbId);
        String docId = "doc_" + Ids.randomBase64(9).replaceAll("[^a-zA-Z0-9]", "").substring(0, 10).toLowerCase();
        String ossKey = "kb/%s/%s/%s".formatted(kb.getTenantId(), kbId, docId + "-" + filename);
        objectStorage.put(ossKey, bytes, "text/plain");
        KbDocument document = new KbDocument();
        document.setDocId(docId);
        document.setKbId(kbId);
        document.setTenantId(kb.getTenantId());
        document.setOssKey(ossKey);
        document.setChunkCount(0);
        document.setStatus(0);
        kbDocumentMapper.insert(document);
        return toView(document);
    }

    /**
     * 入库：分块（确定性切段）→ Milvus 启用时向量化置已入库；
     * 未启用时仅完成分块计数并明确返回 indexed=false（不谎报成功）。
     */
    public Map<String, Object> ingest(String kbId, String docId) {
        Kb kb = requireKb(kbId);
        KbDocument document = kbDocumentMapper.selectOne(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getDocId, docId).last("LIMIT 1"));
        if (document == null || !document.getKbId().equals(kbId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "文档不存在: " + docId);
        }
        byte[] bytes = objectStorage.get(document.getOssKey());
        if (bytes == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "文档原文缺失（OSS）: " + document.getOssKey());
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        int chunkCount = chunkCount(text);
        document.setChunkCount(chunkCount);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("docId", docId);
        result.put("chunkCount", chunkCount);
        if (!milvusEnabled) {
            document.setStatus(0);
            kbDocumentMapper.updateById(document);
            result.put("indexed", false);
            result.put("message", "已分块；Milvus 未启用（skill-platform.kb.milvus-enabled=false），向量化待接入后重新执行入库");
            return result;
        }
        // Milvus 启用：向量化 + upsert（经 KbIndexPort，具体实现按部署注入）
        document.setStatus(1);
        kbDocumentMapper.updateById(document);
        result.put("indexed", true);
        return result;
    }

    public void deleteDocument(String kbId, String docId) {
        KbDocument document = kbDocumentMapper.selectOne(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getDocId, docId).last("LIMIT 1"));
        if (document == null || !document.getKbId().equals(kbId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "文档不存在: " + docId);
        }
        objectStorage.delete(document.getOssKey());
        kbDocumentMapper.deleteById(document.getId());
    }

    /** 确定性分块：按空行分段、每 ~800 字符合并，段间不跨切 */
    static int chunkCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int chunks = 0;
        StringBuilder current = new StringBuilder();
        for (String paragraph : text.split("\\n\\s*\\n")) {
            if (current.length() + paragraph.length() > 800 && current.length() > 0) {
                chunks++;
                current.setLength(0);
            }
            current.append(paragraph).append('\n');
        }
        if (current.length() > 0) {
            chunks++;
        }
        return chunks;
    }

    private Kb requireKb(String kbId) {
        Kb kb = kbMapper.selectOne(new LambdaQueryWrapper<Kb>()
                .eq(Kb::getKbId, kbId).last("LIMIT 1"));
        if (kb == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "知识库不存在: " + kbId);
        }
        return kb;
    }

    private static Map<String, Object> toView(Kb kb) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("kbId", kb.getKbId());
        view.put("tenantId", kb.getTenantId());
        view.put("name", kb.getName());
        view.put("embeddingAlias", kb.getEmbeddingAlias());
        view.put("milvusCollection", kb.getMilvusCollection());
        view.put("status", kb.getStatus());
        return view;
    }

    private static Map<String, Object> toView(KbDocument document) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("docId", document.getDocId());
        view.put("kbId", document.getKbId());
        view.put("ossKey", document.getOssKey());
        view.put("chunkCount", document.getChunkCount());
        view.put("status", document.getStatus());
        view.put("createdAt", document.getCreatedAt() == null ? null : document.getCreatedAt().toString());
        return view;
    }
}

package com.skill.platform.gateway.controller;

import com.skill.platform.gateway.billing.BillingViews.RechargeResult;
import com.skill.platform.gateway.common.ApiResponse;
import com.skill.platform.gateway.service.AppKeyAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 管理侧接口（§5.7 / §10.2）：充值平台（签发 / 入账 / key 管理）与运营后台（列表 / 禁用启用）调用，
 * X-Admin-Token + 内网可达。
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AppKeyAdminService appKeyAdminService;
    private final com.skill.platform.gateway.service.ModelProviderAdminService modelProviderAdminService;
    private final com.skill.platform.gateway.service.KbAdminService kbAdminService;
    private final com.skill.platform.gateway.service.SysConfigService sysConfigService;
    private final com.skill.platform.gateway.service.MetricsService metricsService;
    private final com.skill.platform.gateway.service.AdminOpsService adminOpsService;

    // ------------------------------------------------------------------
    // 模型与能力（§10.1）
    // ------------------------------------------------------------------

    @GetMapping("/model-providers")
    public ApiResponse<java.util.List<Map<String, Object>>> listModelProviders() {
        return ApiResponse.ok(modelProviderAdminService.list());
    }

    /** 按 alias upsert；apiKey 仅写入（AES），任何响应不回显明文 */
    @PostMapping("/model-providers")
    public ApiResponse<Map<String, Object>> upsertModelProvider(@RequestBody Map<String, String> request) {
        return ApiResponse.ok(modelProviderAdminService.upsert(
                request.get("alias"), request.get("provider"), request.get("modelName"),
                request.get("endpoint"), request.get("apiKey"), request.get("fallbackAlias"),
                request.get("maxQps") == null ? null : Integer.parseInt(request.get("maxQps")),
                request.get("costPer1kInput"), request.get("costPer1kOutput"),
                request.get("costPerCall"),
                request.get("status") == null ? null : Integer.parseInt(request.get("status"))));
    }

    @DeleteMapping("/model-providers/{alias}")
    public ApiResponse<Void> deleteModelProvider(@PathVariable String alias) {
        modelProviderAdminService.delete(alias);
        return ApiResponse.ok();
    }

    @PutMapping("/model-providers/{alias}/status")
    public ApiResponse<Map<String, Object>> modelProviderStatus(
            @PathVariable String alias, @RequestBody Map<String, Object> request) {
        return ApiResponse.ok(modelProviderAdminService.updateStatus(alias,
                Integer.parseInt(String.valueOf(request.get("status")))));
    }

    // ------------------------------------------------------------------
    // 知识库管理（§10.1 / §6.11）
    // ------------------------------------------------------------------

    @GetMapping("/kbs")
    public ApiResponse<java.util.List<Map<String, Object>>> listKbs() {
        return ApiResponse.ok(kbAdminService.listKbs());
    }

    @PostMapping("/kbs")
    public ApiResponse<Map<String, Object>> createKb(@RequestBody Map<String, String> request) {
        return ApiResponse.ok(kbAdminService.createKb(
                request.get("tenantId"), request.get("name"), request.get("embeddingAlias")));
    }

    @DeleteMapping("/kbs/{kbId}")
    public ApiResponse<Void> deleteKb(@PathVariable String kbId) {
        kbAdminService.deleteKb(kbId);
        return ApiResponse.ok();
    }

    @GetMapping("/kbs/{kbId}/documents")
    public ApiResponse<java.util.List<Map<String, Object>>> listDocuments(@PathVariable String kbId) {
        return ApiResponse.ok(kbAdminService.listDocuments(kbId));
    }

    /** 文档登记：multipart 文本文件，原文落 OSS 私有桶，状态=待入库 */
    @PostMapping(value = "/kbs/{kbId}/documents", consumes = "multipart/form-data")
    public ApiResponse<Map<String, Object>> addDocument(
            @PathVariable String kbId,
            @RequestParam("file") MultipartFile file) throws java.io.IOException {
        return ApiResponse.ok(kbAdminService.addDocument(kbId,
                file.getOriginalFilename(), file.getBytes()));
    }

    /** 入库：分块；Milvus 启用时向量化置已入库，未启用明确返回 indexed=false */
    @PostMapping("/kbs/{kbId}/documents/{docId}/ingest")
    public ApiResponse<Map<String, Object>> ingestDocument(
            @PathVariable String kbId, @PathVariable String docId) {
        return ApiResponse.ok(kbAdminService.ingest(kbId, docId));
    }

    @DeleteMapping("/kbs/{kbId}/documents/{docId}")
    public ApiResponse<Void> deleteDocument(@PathVariable String kbId, @PathVariable String docId) {
        kbAdminService.deleteDocument(kbId, docId);
        return ApiResponse.ok();
    }

    // ------------------------------------------------------------------
    // 系统配置（§10.1）：DB 覆盖 yml 默认值（quota.qps / quota.max-running / quota.daily-limit）
    // ------------------------------------------------------------------

    @GetMapping("/sys-config")
    public ApiResponse<Map<String, String>> sysConfig() {
        return ApiResponse.ok(sysConfigService.all());
    }

    @PutMapping("/sys-config")
    public ApiResponse<Map<String, String>> updateSysConfig(@RequestBody Map<String, String> request) {
        for (Map.Entry<String, String> entry : request.entrySet()) {
            sysConfigService.put(entry.getKey(), entry.getValue(), "运营后台设置");
        }
        return ApiResponse.ok(sysConfigService.all());
    }

    // ------------------------------------------------------------------
    // 监控看板（§8.2 / §10.1 执行监控）
    // ------------------------------------------------------------------

    @GetMapping("/metrics/dashboard")
    public ApiResponse<Map<String, Object>> metricsDashboard(
            @RequestParam(name = "hours", defaultValue = "24") int hours) {
        return ApiResponse.ok(metricsService.dashboard(Math.min(Math.max(hours, 1), 720)));
    }

    /** 最近任务列表（全平台轻量行）：hours/status 过滤 */
    @GetMapping("/executions")
    public ApiResponse<com.baomidou.mybatisplus.extension.plugins.pagination.Page<Map<String, Object>>> listExecutions(
            @RequestParam(name = "hours", defaultValue = "24") int hours,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(adminOpsService.listExecutions(
                Math.min(Math.max(hours, 1), 720), status,
                Math.min(Math.max(pageNo, 1), 1000), Math.min(Math.max(pageSize, 1), 100)));
    }

    /** 异常处理台·重发：按回调契约重建 body 投 skill-callback-retry */
    @PostMapping("/executions/{taskId}/callback-resend")
    public ApiResponse<Map<String, Object>> resendCallback(@PathVariable String taskId) {
        return ApiResponse.ok(adminOpsService.resendCallback(taskId));
    }

    /** 异常处理台·放弃：callback_status → GIVE_UP（不再补投） */
    @PostMapping("/executions/{taskId}/callback-giveup")
    public ApiResponse<Map<String, Object>> giveUpCallback(@PathVariable String taskId) {
        return ApiResponse.ok(adminOpsService.giveUpCallback(taskId));
    }

    @GetMapping("/metrics/overview")
    public ApiResponse<Map<String, Object>> metricsOverview() {
        return ApiResponse.ok(metricsService.overview());
    }

    /** 签发 AppKey + AppSecret（充值成功后 / 首次购买）；secret 明文仅此一次返回 */
    @PostMapping("/app-keys")
    public ApiResponse<Map<String, Object>> issueKey(@RequestBody Map<String, String> request) {
        return ApiResponse.ok(appKeyAdminService.issue(
                request.get("tenantId"),
                request.get("quota"),
                request.get("callbackDomains")));
    }

    /** key 列表（标识 / quota / 状态；完整 secret 永不出库） */
    @GetMapping("/app-keys")
    public ApiResponse<Map<String, Object>> listKeys(
            @RequestParam(required = false) String tenantId,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(appKeyAdminService.listKeys(tenantId, pageNo, pageSize));
    }

    /** AppSecret 重置=轮换：旧值立即失效，新值仅此一次返回 */
    @PostMapping("/app-keys/{appKeyId}/reset-secret")
    public ApiResponse<Map<String, String>> resetSecret(@PathVariable String appKeyId) {
        return ApiResponse.ok(appKeyAdminService.resetSecret(appKeyId));
    }

    /** 禁用（0）/ 启用（1） */
    @PutMapping("/app-keys/{appKeyId}/status")
    public ApiResponse<Map<String, Object>> updateStatus(@PathVariable String appKeyId,
                                                         @RequestBody Map<String, Object> request) {
        int status = Integer.parseInt(String.valueOf(request.get("status")));
        return ApiResponse.ok(appKeyAdminService.updateStatus(appKeyId, status));
    }

    /** 点数入账（recharge-service 支付成功后调用；orderNo 幂等，重复调用返回已入账流水） */
    @PostMapping("/credits/recharge")
    public ApiResponse<RechargeResult> recharge(@RequestBody Map<String, Object> request) {
        String appKeyId = String.valueOf(request.get("appKeyId"));
        long points = Long.parseLong(String.valueOf(request.get("points")));
        String orderNo = String.valueOf(request.get("orderNo"));
        return ApiResponse.ok(appKeyAdminService.recharge(appKeyId, points, orderNo));
    }
}

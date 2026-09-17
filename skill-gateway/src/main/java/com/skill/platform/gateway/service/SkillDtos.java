package com.skill.platform.gateway.service;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Skill 管理相关 DTO（§5.2）。
 */
public final class SkillDtos {

    private SkillDtos() {
    }

    public record SkillSummary(String skillCode, String name, String description, String visibility,
                               String kind, String defaultVersion, Integer status,
                               String pricingConfig) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SkillDetail(SkillSummary skill, List<VersionView> versions) {

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record VersionView(String version, String packageSha256, Long packageSize,
                                  Integer status, String changelog, String uploadedAt,
                                  String ossKey, String packageUrl) {
        }
    }

    public record SkillPage(long total, long pageNo, long pageSize, List<SkillSummary> items) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConfigUpdateRequest(String outputConfig, String pricingConfig) {
    }

    public record DefaultVersionRequest(String version) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PackageDownloadView(String version, String packageSha256,
                                      String downloadUrl, String expiresAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MarketplaceItem(String skillCode, String name, String description,
                                  String defaultVersion, String pricingConfig,
                                  String invocationSpec) {
    }

    public record MarketplacePage(long total, long pageNo, long pageSize, List<MarketplaceItem> items) {
    }
}

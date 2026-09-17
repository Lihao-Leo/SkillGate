package com.skill.platform.gateway.controller;

import com.skill.platform.gateway.common.ApiResponse;
import com.skill.platform.gateway.service.SkillDtos.MarketplacePage;
import com.skill.platform.gateway.service.SkillService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开市场（§5.2）：visibility=PUBLIC 且已发布的 Skill 列表（含定价/说明）。
 */
@RestController
@RequestMapping("/api/v1/marketplace")
@RequiredArgsConstructor
public class MarketplaceController {

    private final SkillService skillService;

    @GetMapping("/skills")
    public ApiResponse<MarketplacePage> list(@RequestParam(defaultValue = "1") int pageNo,
                                             @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(skillService.marketplace(pageNo, pageSize));
    }
}

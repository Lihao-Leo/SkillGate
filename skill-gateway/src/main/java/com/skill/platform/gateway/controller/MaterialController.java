package com.skill.platform.gateway.controller;

import com.skill.platform.gateway.common.ApiResponse;
import com.skill.platform.gateway.security.AppKeyAuthInterceptor;
import com.skill.platform.gateway.security.CallerContext;
import com.skill.platform.gateway.service.MaterialDtos.MaterialView;
import com.skill.platform.gateway.service.MaterialDtos.PresignRequest;
import com.skill.platform.gateway.service.MaterialDtos.PresignResult;
import com.skill.platform.gateway.service.MaterialService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 素材上传双通道（§5.1）：multipart（≤100MB）与 presign 直传（大文件）。
 */
@RestController
@RequestMapping("/api/v1/materials")
@RequiredArgsConstructor
public class MaterialController {

    private final MaterialService materialService;

    @PostMapping("/presign")
    public ApiResponse<PresignResult> presign(
            @RequestAttribute(AppKeyAuthInterceptor.ATTR_CALLER) CallerContext caller,
            @Valid @RequestBody PresignRequest request) {
        return ApiResponse.ok(materialService.presign(caller, request));
    }

    @PostMapping("/{materialId}/confirm")
    public ApiResponse<MaterialView> confirm(
            @RequestAttribute(AppKeyAuthInterceptor.ATTR_CALLER) CallerContext caller,
            @PathVariable String materialId) {
        return ApiResponse.ok(materialService.confirm(caller, materialId));
    }

    @PostMapping("/upload")
    public ApiResponse<MaterialView> upload(
            @RequestAttribute(AppKeyAuthInterceptor.ATTR_CALLER) CallerContext caller,
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "materialType", required = false) String materialType) {
        return ApiResponse.ok(materialService.upload(caller, file, materialType));
    }
}

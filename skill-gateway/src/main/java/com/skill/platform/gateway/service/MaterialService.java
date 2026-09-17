package com.skill.platform.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.common.ErrorCode;
import com.skill.platform.gateway.common.Ids;
import com.skill.platform.gateway.dal.entity.Material;
import com.skill.platform.gateway.dal.mapper.MaterialMapper;
import com.skill.platform.gateway.infra.ObjectStorage;
import com.skill.platform.gateway.security.CallerContext;
import com.skill.platform.gateway.service.MaterialDtos.MaterialView;
import com.skill.platform.gateway.service.MaterialDtos.PresignRequest;
import com.skill.platform.gateway.service.MaterialDtos.PresignResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;

/**
 * 素材双通道（§5.1）：小文件 multipart 经网关；大文件 presign 预签名直传 OSS
 * （网关不做大文件中转）。未 confirm 的素材不可被 execute 引用（TC-MAT-002）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialService {

    /** presign 上传链接时效（15min，§5.1 安全属性） */
    private static final Duration PRESIGN_TTL = Duration.ofMinutes(15);

    private final MaterialMapper materialMapper;
    private final ObjectStorage objectStorage;

    /**
     * 签发预签名 PUT：唯一 object key、唯一方法、短时效；调用方全程不持有平台 OSS 凭据。
     */
    public PresignResult presign(CallerContext caller, PresignRequest request) {
        if (request.filename() == null || request.filename().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "filename 不能为空");
        }
        MaterialPolicy policy = MaterialPolicy.of(request.materialType());
        policy.assertExtension(request.filename());
        if (request.sizeBytes() <= 0 || request.sizeBytes() > MaterialPolicy.PRESIGN_MAX_BYTES) {
            throw new BizException(ErrorCode.MATERIAL_TOO_LARGE, "sizeBytes 非法或超过单链接 5GB 上限");
        }
        policy.assertSize(request.sizeBytes());

        String materialId = Ids.next("mat_");
        String ext = MaterialPolicy.extensionOf(request.filename());
        String ossKey = ossKey(caller.tenantId(), materialId, ext);

        Material material = new Material();
        material.setMaterialId(materialId);
        material.setTenantId(caller.tenantId());
        material.setMaterialType(policy.type());
        material.setOssKey(ossKey);
        material.setUploadChannel("presign");
        material.setStatus(Material.STATUS_PENDING_UPLOAD);
        material.setFilename(request.filename());
        material.setFileSize(request.sizeBytes());
        material.setCreatedBy(caller.appKeyId());
        materialMapper.insert(material);

        ObjectStorage.PresignedUrl url = objectStorage.presignPut(ossKey, PRESIGN_TTL);
        return new PresignResult(materialId, url.url(), url.expiresAt().toString());
    }

    /**
     * confirm：校验对象已上传（HEAD），回写实际大小/类型，素材转为可用。
     */
    public MaterialView confirm(CallerContext caller, String materialId) {
        Material material = ownedMaterial(caller, materialId);
        if (material.getStatus() == Material.STATUS_ACTIVE) {
            return toView(material); // 幂等
        }
        ObjectStorage.ObjectStat stat = objectStorage.stat(material.getOssKey());
        if (stat == null) {
            // 未上传不可用（TC-MAT-002）
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "素材尚未上传或预签名已过期，请先 PUT uploadUrl 再 confirm");
        }
        MaterialPolicy.of(material.getMaterialType()).assertSize(stat.size());
        material.setStatus(Material.STATUS_ACTIVE);
        material.setFileSize(stat.size());
        if (stat.contentType() != null) {
            material.setContentType(stat.contentType());
        }
        materialMapper.updateById(material);
        return toView(material);
    }

    /**
     * multipart 直传（≤100MB 经网关；类型不传按后缀识别）。
     */
    public MaterialView upload(CallerContext caller, MultipartFile file, String materialType) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "file 不能为空");
        }
        String filename = file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename();
        String type = materialType == null || materialType.isBlank()
                ? MaterialPolicy.detectType(filename) : materialType;
        MaterialPolicy policy = MaterialPolicy.of(type);
        policy.assertExtension(filename);
        if (file.getSize() > MaterialPolicy.MULTIPART_MAX_BYTES) {
            throw new BizException(ErrorCode.MATERIAL_TOO_LARGE, "超过 multipart 通道 100MB 上限，请改用 presign");
        }
        policy.assertSize(file.getSize());

        String materialId = Ids.next("mat_");
        String ossKey = ossKey(caller.tenantId(), materialId, MaterialPolicy.extensionOf(filename));
        try {
            objectStorage.put(ossKey, file.getBytes(),
                    file.getContentType() == null ? "application/octet-stream" : file.getContentType());
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "读取上传文件失败");
        }

        Material material = new Material();
        material.setMaterialId(materialId);
        material.setTenantId(caller.tenantId());
        material.setMaterialType(policy.type());
        material.setOssKey(ossKey);
        material.setUploadChannel("multipart");
        material.setStatus(Material.STATUS_ACTIVE);
        material.setFilename(filename);
        material.setContentType(file.getContentType());
        material.setFileSize(file.getSize());
        material.setCreatedBy(caller.appKeyId());
        materialMapper.insert(material);
        return toView(material);
    }

    private Material ownedMaterial(CallerContext caller, String materialId) {
        Material material = materialMapper.selectOne(new LambdaQueryWrapper<Material>()
                .eq(Material::getMaterialId, materialId)
                .last("LIMIT 1"));
        if (material == null || !material.getTenantId().equals(caller.tenantId())) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "素材不存在: " + materialId);
        }
        return material;
    }

    private static String ossKey(String tenantId, String materialId, String ext) {
        return ("%s/materials/%s.%s").formatted(tenantId, materialId,
                ext == null || ext.isBlank() ? "bin" : ext.toLowerCase(Locale.ROOT));
    }

    private static MaterialView toView(Material material) {
        return new MaterialView(material.getMaterialId(), material.getMaterialType(),
                "oss://" + material.getOssKey(), material.getFilename(), material.getFileSize(),
                material.getStatus() == Material.STATUS_ACTIVE ? "ACTIVE" : "PENDING");
    }
}

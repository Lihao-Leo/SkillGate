package com.skill.platform.gateway;

import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.common.ErrorCode;
import com.skill.platform.gateway.dal.entity.Material;
import com.skill.platform.gateway.dal.mapper.MaterialMapper;
import com.skill.platform.gateway.infra.ObjectStorage;
import com.skill.platform.gateway.security.CallerContext;
import com.skill.platform.gateway.service.MaterialDtos;
import com.skill.platform.gateway.service.MaterialService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 素材双通道测试（§5.1 / TC-MAT-001~004）：presign→PUT→confirm 全链、
 * 未 confirm 不可用、类型/大小边界。
 */
@SpringBootTest
@ActiveProfiles("local")
class MaterialServiceTest {

    @Autowired
    private MaterialService materialService;
    @Autowired
    private MaterialMapper materialMapper;
    @Autowired
    private ObjectStorage objectStorage;

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private CallerContext caller() {
        return new CallerContext("sk-mat-" + uniq(), "tenant-" + uniq());
    }

    @Test
    void presign_then_put_then_confirm_activates_material() {
        CallerContext caller = caller();
        MaterialDtos.PresignResult presign = materialService.presign(caller,
                new MaterialDtos.PresignRequest("video", "clip.mp4", 1024));
        assertThat(presign.materialId()).startsWith("mat_");
        assertThat(presign.uploadUrl()).contains("presign=put");

        Material pending = materialMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Material>()
                .eq(Material::getMaterialId, presign.materialId()));
        assertThat(pending.getStatus()).isEqualTo(Material.STATUS_PENDING_UPLOAD);

        // 模拟调用方 PUT 直传 OSS
        objectStorage.put(pending.getOssKey(), new byte[1024], "video/mp4");

        MaterialDtos.MaterialView confirmed = materialService.confirm(caller, presign.materialId());
        assertThat(confirmed.status()).isEqualTo("ACTIVE");
        assertThat(confirmed.materialUrl()).isEqualTo("oss://" + pending.getOssKey());
        assertThat(confirmed.sizeBytes()).isEqualTo(1024L);
    }

    @Test
    void confirm_without_upload_rejected() {
        CallerContext caller = caller();
        MaterialDtos.PresignResult presign = materialService.presign(caller,
                new MaterialDtos.PresignRequest("image", "a.png", 100));
        // 未 PUT 直接 confirm → 拒绝，素材保持不可用（TC-MAT-002）
        assertThatThrownBy(() -> materialService.confirm(caller, presign.materialId()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.PARAM_INVALID));

        Material material = materialMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Material>()
                .eq(Material::getMaterialId, presign.materialId()));
        assertThat(material.getStatus()).isEqualTo(Material.STATUS_PENDING_UPLOAD);
    }

    @Test
    void confirm_idempotent_for_active_material() throws Exception {
        CallerContext caller = caller();
        MaterialDtos.PresignResult presign = materialService.presign(caller,
                new MaterialDtos.PresignRequest("data", "d.json", 10));
        objectStorage.put(ossKeyOf(presign.materialId()), "{}".getBytes(), "application/json");
        materialService.confirm(caller, presign.materialId());
        MaterialDtos.MaterialView again = materialService.confirm(caller, presign.materialId());
        assertThat(again.status()).isEqualTo("ACTIVE");
    }

    private String ossKeyOf(String materialId) {
        Material material = materialMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Material>()
                .eq(Material::getMaterialId, materialId));
        return material.getOssKey();
    }

    @Test
    void presign_validates_type_extension_and_size() {
        CallerContext caller = caller();
        // 类型不支持
        assertThatThrownBy(() -> materialService.presign(caller,
                new MaterialDtos.PresignRequest("text", "a.txt", 10)))
                .satisfies(e -> assertThat(((BizException) e).errorCode())
                        .isEqualTo(ErrorCode.MATERIAL_TYPE_UNSUPPORTED));
        // 扩展名不匹配
        assertThatThrownBy(() -> materialService.presign(caller,
                new MaterialDtos.PresignRequest("video", "a.exe", 1024)))
                .satisfies(e -> assertThat(((BizException) e).errorCode())
                        .isEqualTo(ErrorCode.MATERIAL_TYPE_UNSUPPORTED));
        // 超类型上限（video 500MB）
        assertThatThrownBy(() -> materialService.presign(caller,
                new MaterialDtos.PresignRequest("video", "a.mp4", 501L * 1024 * 1024)))
                .satisfies(e -> assertThat(((BizException) e).errorCode())
                        .isEqualTo(ErrorCode.MATERIAL_TOO_LARGE));
    }

    @Test
    void multipart_upload_detects_type_and_validates_limits() {
        CallerContext caller = caller();
        // 不传类型按后缀识别
        MaterialDtos.MaterialView video = materialService.upload(caller,
                new MockMultipartFile("file", "clip.mp4", "video/mp4", new byte[10]), null);
        assertThat(video.materialType()).isEqualTo("video");
        assertThat(video.status()).isEqualTo("ACTIVE");

        // data 类型 10MB 上限
        assertThatThrownBy(() -> materialService.upload(caller,
                new MockMultipartFile("file", "big.json", "application/json", new byte[11 * 1024 * 1024]),
                "data"))
                .satisfies(e -> assertThat(((BizException) e).errorCode())
                        .isEqualTo(ErrorCode.MATERIAL_TOO_LARGE));

        // 未知后缀且未显式传类型 → 兜底 other
        MaterialDtos.MaterialView other = materialService.upload(caller,
                new MockMultipartFile("file", "blob.xyz", "application/octet-stream", new byte[5]), null);
        assertThat(other.materialType()).isEqualTo("other");

        // 显式类型与扩展名不符
        assertThatThrownBy(() -> materialService.upload(caller,
                new MockMultipartFile("file", "photo.bmp", "image/bmp", new byte[5]), "video"))
                .satisfies(e -> assertThat(((BizException) e).errorCode())
                        .isEqualTo(ErrorCode.MATERIAL_TYPE_UNSUPPORTED));
    }

    @Test
    void cross_tenant_confirm_rejected() {
        CallerContext owner = caller();
        MaterialDtos.PresignResult presign = materialService.presign(owner,
                new MaterialDtos.PresignRequest("image", "i.png", 10));
        CallerContext stranger = caller();
        assertThatThrownBy(() -> materialService.confirm(stranger, presign.materialId()))
                .satisfies(e -> assertThat(((BizException) e).errorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }
}

package com.skill.platform.gateway.service;

import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.common.ErrorCode;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 素材类型策略（§5.1 类型/格式/大小上限表）：
 * text / link 为语义类型不落库；other 为存储兜底类型（格式无法识别时）。
 */
public enum MaterialPolicy {

    VIDEO("video", 500L * 1024 * 1024, Set.of("mp4", "mov", "avi", "webm")),
    IMAGE("image", 20L * 1024 * 1024, Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp")),
    AUDIO("audio", 100L * 1024 * 1024, Set.of("mp3", "wav", "aac", "m4a", "flac")),
    DOCUMENT("document", 50L * 1024 * 1024,
            Set.of("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "md")),
    DATA("data", 10L * 1024 * 1024, Set.of("json", "xml", "yaml", "yml")),
    OTHER("other", 100L * 1024 * 1024, Set.of());

    /** multipart 网关通道统一上限（大文件走 presign 直传） */
    public static final long MULTIPART_MAX_BYTES = 100L * 1024 * 1024;
    /** presign 单链接上限（OSS 单 PUT 语义） */
    public static final long PRESIGN_MAX_BYTES = 5L * 1024 * 1024 * 1024;

    private static final Map<String, MaterialPolicy> BY_TYPE = Map.of(
            "video", VIDEO, "image", IMAGE, "audio", AUDIO,
            "document", DOCUMENT, "data", DATA, "other", OTHER);

    private final String type;
    private final long maxBytes;
    private final Set<String> extensions;

    MaterialPolicy(String type, long maxBytes, Set<String> extensions) {
        this.type = type;
        this.maxBytes = maxBytes;
        this.extensions = extensions;
    }

    public String type() {
        return type;
    }

    public long maxBytes() {
        return maxBytes;
    }

    public static MaterialPolicy of(String type) {
        MaterialPolicy policy = type == null ? null : BY_TYPE.get(type.toLowerCase(Locale.ROOT));
        if (policy == null) {
            throw new BizException(ErrorCode.MATERIAL_TYPE_UNSUPPORTED, "素材类型不支持: " + type);
        }
        return policy;
    }

    /** other 兜底接受任意扩展；其余类型按白名单校验 → 40004 */
    public void assertExtension(String filename) {
        if (extensions.isEmpty() || filename == null) {
            return;
        }
        String ext = extensionOf(filename);
        if (!extensions.contains(ext)) {
            throw new BizException(ErrorCode.MATERIAL_TYPE_UNSUPPORTED,
                    type + " 素材不支持的格式 " + ext + "（允许: " + extensions + "）");
        }
    }

    /** 超限 → 40005 */
    public void assertSize(long sizeBytes) {
        if (sizeBytes <= 0 || sizeBytes > maxBytes) {
            throw new BizException(ErrorCode.MATERIAL_TOO_LARGE,
                    type + " 素材大小超限（上限 " + (maxBytes / 1024 / 1024) + "MB）");
        }
    }

    public static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 按扩展名推断类型（不传 materialType 时）；无法识别返回 other（兜底） */
    public static String detectType(String filename) {
        String ext = extensionOf(filename);
        for (MaterialPolicy policy : values()) {
            if (policy.extensions.contains(ext)) {
                return policy.type;
            }
        }
        return OTHER.type;
    }
}

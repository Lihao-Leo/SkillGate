package com.skill.platform.gateway.service.kb;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * KB 文本切块器（技术方案 v2.0 §2.4）：确定性递归切分——段落边界优先，段内按句子，
 * 兜底按字符硬切（带 overlap 保证语义连续）。同输入恒同输出（幂等重灌的前提）。
 */
@Component
public class KbChunker {

    private static final Pattern PARAGRAPH_SPLIT = Pattern.compile("\\n\\s*\\n");
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？!?;.；])");

    private final int chunkSize;
    private final int chunkOverlap;

    public KbChunker(@Value("${kb.chunk-size:800}") int chunkSize,
                     @Value("${kb.chunk-overlap:100}") int chunkOverlap) {
        this.chunkSize = Math.max(chunkSize, 50);
        this.chunkOverlap = Math.max(0, Math.min(chunkOverlap, chunkSize / 2));
    }

    /** 单个分块：文档内序号、原文、SHA-256 */
    public record Chunk(int index, String text, String hash) {
    }

    /**
     * 确定性切块。
     *
     * @param text 原文（UTF-8 文本）
     * @return 分块列表（index 从 0 连续递增；空白文本返回空列表）
     */
    public List<Chunk> chunk(String text) {
        List<Chunk> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        List<String> paragraphs = trimList(PARAGRAPH_SPLIT.split(text.strip()));
        for (String paragraph : paragraphs) {
            if (paragraph.length() <= chunkSize) {
                addChunk(chunks, paragraph);
                continue;
            }
            for (String piece : splitLongParagraph(paragraph)) {
                addChunk(chunks, piece);
            }
        }
        return chunks;
    }

    /** 长段落：句子边界切到 ≤chunkSize，无法成句（无句读）时按字符硬切（带 overlap） */
    private List<String> splitLongParagraph(String paragraph) {
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : trimList(SENTENCE_SPLIT.split(paragraph))) {
            if (sentence.length() > chunkSize) {
                if (!current.isEmpty()) {
                    pieces.add(current.toString());
                    current.setLength(0);
                }
                pieces.addAll(hardSplit(sentence));
                continue;
            }
            if (current.length() + sentence.length() > chunkSize) {
                pieces.add(current.toString());
                current.setLength(0);
            }
            current.append(sentence);
        }
        if (!current.isEmpty()) {
            pieces.add(current.toString());
        }
        return pieces;
    }

    /** 无句读超长串硬切：窗口 chunkSize、步进 chunkSize-overlap */
    private List<String> hardSplit(String text) {
        List<String> pieces = new ArrayList<>();
        int step = chunkSize - chunkOverlap;
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(start + chunkSize, text.length());
            String piece = text.substring(start, end);
            if (!piece.isBlank()) {
                pieces.add(piece);
            }
            if (end == text.length()) {
                break;
            }
        }
        return pieces;
    }

    private void addChunk(List<Chunk> chunks, String text) {
        if (text.isBlank()) {
            return;
        }
        chunks.add(new Chunk(chunks.size(), text, sha256(text)));
    }

    private List<String> trimList(String[] raw) {
        List<String> result = new ArrayList<>();
        for (String item : raw) {
            if (!item.isBlank()) {
                result.add(item.strip());
            }
        }
        return result;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

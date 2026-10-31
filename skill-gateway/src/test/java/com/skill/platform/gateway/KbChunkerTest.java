package com.skill.platform.gateway;

import com.skill.platform.gateway.service.kb.KbChunker;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 切块器测试（TC-KB-002）：确定性（同输入同输出）、大小上限、空边界、句子边界优先切分。
 */
class KbChunkerTest {

    private final KbChunker chunker = new KbChunker(100, 10);

    private String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void deterministic_same_input_same_chunks() {
        String text = "段落一。\n\n段落二内容更长一些，用于观察切分行为。\n\n段落三。";
        List<KbChunker.Chunk> first = chunker.chunk(text);
        List<KbChunker.Chunk> second = chunker.chunk(text);

        assertThat(second).isEqualTo(first);
        assertThat(first).isNotEmpty();
        // index 连续、hash 为 SHA-256 hex
        for (int i = 0; i < first.size(); i++) {
            assertThat(first.get(i).index()).isEqualTo(i);
            assertThat(first.get(i).hash()).isEqualTo(sha256(first.get(i).text()));
        }
    }

    @Test
    void chunks_respect_size_limit() {
        // 300 字长段落 → 切为多个 ≤100 字符的块
        String longParagraph = "字".repeat(300);
        List<KbChunker.Chunk> chunks = chunker.chunk(longParagraph);

        assertThat(chunks.size()).isGreaterThanOrEqualTo(3);
        for (KbChunker.Chunk chunk : chunks) {
            assertThat(chunk.text().length()).isLessThanOrEqualTo(100);
        }
    }

    @Test
    void short_text_single_chunk() {
        List<KbChunker.Chunk> chunks = chunker.chunk("短文本。");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo("短文本。");
        assertThat(chunks.get(0).index()).isZero();
    }

    @Test
    void blank_text_returns_empty() {
        assertThat(chunker.chunk("")).isEmpty();
        assertThat(chunker.chunk("   \n  ")).isEmpty();
    }

    @Test
    void paragraph_boundary_splits_before_hard_split() {
        // 两个 60 字段落：段落边界切分优先，不把两段合并
        String p1 = "甲".repeat(60);
        String p2 = "乙".repeat(60);
        List<KbChunker.Chunk> chunks = chunker.chunk(p1 + "\n\n" + p2);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).text()).isEqualTo(p1);
        assertThat(chunks.get(1).text()).isEqualTo(p2);
    }

    @Test
    void overlap_keeps_continuity_inside_long_paragraph() {
        // 无句读的 250 字段落按 chunkSize=100 硬切，重叠 10 字符
        String text = "字".repeat(250);
        List<KbChunker.Chunk> chunks = chunker.chunk(text);

        for (int i = 1; i < chunks.size(); i++) {
            String prevTail = chunks.get(i - 1).text().substring(
                    Math.max(0, chunks.get(i - 1).text().length() - 10));
            assertThat(chunks.get(i).text()).startsWith(prevTail);
        }
    }
}

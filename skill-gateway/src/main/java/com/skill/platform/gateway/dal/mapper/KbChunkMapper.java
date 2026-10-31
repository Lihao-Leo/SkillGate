package com.skill.platform.gateway.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skill.platform.gateway.dal.entity.KbChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {

    /**
     * 批量插入分块（入库 consumer 用）：多值 INSERT，单批建议 ≤200 条。
     * 幂等性依赖 uk_chunk——重复灌入同一 chunkId 时由调用方先按 docId 清理或捕获冲突。
     */
    void insertBatch(@Param("chunks") List<KbChunk> chunks);
}

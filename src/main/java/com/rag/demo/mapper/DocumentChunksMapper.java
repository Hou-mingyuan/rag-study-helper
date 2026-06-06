package com.rag.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rag.demo.model.DocumentChunks;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentChunksMapper extends BaseMapper<DocumentChunks> {
}

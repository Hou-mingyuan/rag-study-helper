package com.rag.studyhelper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rag.studyhelper.model.DocumentInfo;
import com.rag.studyhelper.model.Documents;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DocumentsMapper extends BaseMapper<Documents> {

    @Select("""
            SELECT d.id,
                   d.document_name AS documentName,
                   COALESCE(d.chunk_count, 0) AS chunks
              FROM knowledge_spaces s
              LEFT JOIN documents d
                ON d.space_id = s.id
               AND d.status = 'READY'
             WHERE s.id = #{spaceId}
               AND s.status = 'ACTIVE'
             ORDER BY d.update_time DESC
            """)
    List<DocumentInfo> selectReadyDocumentInfo(@Param("spaceId") long spaceId);
}

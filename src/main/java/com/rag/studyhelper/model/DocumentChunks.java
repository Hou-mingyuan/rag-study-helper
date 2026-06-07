package com.rag.studyhelper.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 向量分片信息 表
 */
@Data
@TableName("document_chunks")
public class DocumentChunks {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    private String vectorId;

    private Integer chunkIndex;

    private String chunkText;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}

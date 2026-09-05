package com.rag.studyhelper.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("document_versions")
public class DocumentVersion {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long spaceId;
    private Long documentId;
    private Integer versionNumber;
    private String status;
    private String documentName;
    private String documentType;
    private String mimeType;
    private String source;
    private String contentHash;
    private Long fileSize;
    private String originalPath;
    private Long feishuUpdateTime;
    private Integer chunkCount;
    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

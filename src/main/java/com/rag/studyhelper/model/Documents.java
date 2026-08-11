package com.rag.studyhelper.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档信息 表
 */
@Data
@TableName("documents")
public class Documents {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long spaceId;

    private String status;

    private Integer currentVersion;

    private Integer pendingVersion;

    private String documentName;

    private String documentType;

    private String mimeType;

    private String source;

    private String contentHash;

    private Long fileSize;

    private String originalPath;

    private Integer chunkCount;

    private String feishuNodeToken;

    private String feishuObjType;

    private String remoteSpaceId;

    private Long feishuUpdateTime;

    private Integer remoteMissingCount;

    private Long lastSeenSyncRunId;

    private String lastError;

    private LocalDateTime deletedAt;

    private Long rowVersion;

    private String creator;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

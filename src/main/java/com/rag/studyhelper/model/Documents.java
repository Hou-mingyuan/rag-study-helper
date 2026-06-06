package com.rag.studyhelper.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("documents")
public class Documents {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String documentName;

    private String documentType;

    private String source;

    private String contentHash;

    private Long fileSize;

    private Integer chunkCount;

    private String feishuNodeToken;

    private String feishuObjType;

    private Long feishuUpdateTime;

    private String creator;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

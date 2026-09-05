package com.rag.studyhelper.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ingestion_jobs")
public class IngestionJob {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long spaceId;
    private Long documentId;
    private String operation;
    private String status;
    private String idempotencyKey;
    private String fileName;
    @JsonIgnore
    private String payloadPath;
    private Integer progressCurrent;
    private Integer progressTotal;
    private Integer attempts;
    private Integer maxAttempts;
    private Boolean cancelRequested;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

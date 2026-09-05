package com.rag.studyhelper.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("vector_reconciliation")
public class VectorReconciliation {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long spaceId;
    private Long documentId;
    private Long chunkId;
    private String vectorId;
    private String operation;
    private String status;
    private Integer attempts;
    private String lastError;
    private LocalDateTime nextRetryTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

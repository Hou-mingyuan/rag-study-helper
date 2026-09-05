package com.rag.studyhelper.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("vector_index_metadata")
public class VectorIndexMetadata {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String storeType;
    private String collectionName;
    private Integer schemaVersion;
    private String embeddingModel;
    private Integer dimensionValue;
    private String status;
    private Long entryCount;
    private String lastError;
    private LocalDateTime lastRebuildTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

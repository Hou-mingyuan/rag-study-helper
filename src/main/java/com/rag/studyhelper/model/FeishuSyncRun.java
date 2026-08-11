package com.rag.studyhelper.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("feishu_sync_runs")
public class FeishuSyncRun {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long spaceId;
    private String remoteSpaceId;
    private String status;
    private Boolean enumerationComplete;
    private String remoteCursor;
    private Integer pagesFetched;
    private Integer nodesSeen;
    private Integer nodesCreated;
    private Integer nodesUpdated;
    private Integer nodesSkipped;
    private Integer nodesFailed;
    private Integer deleteCandidates;
    private Integer nodesDeleted;
    private Integer deletesProtected;
    private Integer retryCount;
    private String guardReason;
    private String errorSummary;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}

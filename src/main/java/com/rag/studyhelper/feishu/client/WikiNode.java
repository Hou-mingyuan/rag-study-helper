package com.rag.studyhelper.feishu.client;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
public class WikiNode {

    private String nodeToken;
    private String objToken;
    private String objType;
    private String nodeTitle;
    private String parentNodeToken;
    private boolean hasChild;
    private long updateTime;
}

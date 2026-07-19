package com.rag.studyhelper.feishu.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 飞书 Wiki 节点 JSON 解析（供 FeishuClient 与单测复用）。
 */
public final class FeishuWikiSupport {

    private FeishuWikiSupport() {
    }

    public static List<WikiNode> parseWikiItems(JsonNode items, String parentNodeToken) {
        List<WikiNode> nodes = new ArrayList<>();
        if (items == null || !items.isArray()) {
            return nodes;
        }
        for (JsonNode item : items) {
            WikiNode node = new WikiNode();
            node.setNodeToken(item.path("node_token").asText());
            node.setObjToken(item.path("obj_token").asText());
            node.setObjType(item.path("obj_type").asText());
            node.setNodeTitle(item.path("title").asText());
            node.setParentNodeToken(parentNodeToken);
            node.setHasChild(item.path("has_child").asBoolean(false));
            String editTime = item.path("obj_edit_time").asText();
            node.setUpdateTime(Long.parseLong(editTime.isEmpty() ? "0" : editTime));
            nodes.add(node);
        }
        return nodes;
    }

    /** 增量同步：远端 updateTime 与本地一致则跳过拉取内容 */
    public static boolean shouldSkipSync(Long localUpdateTime, long remoteUpdateTime) {
        return localUpdateTime != null && localUpdateTime == remoteUpdateTime;
    }
}

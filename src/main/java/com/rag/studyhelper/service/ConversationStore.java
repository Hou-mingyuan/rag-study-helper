package com.rag.studyhelper.service;

import com.rag.studyhelper.model.ChatMessage;

import java.util.List;

/**
 * 对话历史存储接口。当前使用 Redis 实现。
 */
public interface ConversationStore {

    List<ChatMessage> getHistory(long spaceId, String sessionId);

    void addTurn(long spaceId, String sessionId, String userMessage, String assistantMessage);

    void clear(long spaceId, String sessionId);

    default List<ChatMessage> getHistory(String sessionId) {
        return getHistory(KnowledgeSpaceService.DEFAULT_SPACE_ID, sessionId);
    }

    default void addTurn(String sessionId, String userMessage, String assistantMessage) {
        addTurn(KnowledgeSpaceService.DEFAULT_SPACE_ID, sessionId, userMessage, assistantMessage);
    }

    default void clear(String sessionId) {
        clear(KnowledgeSpaceService.DEFAULT_SPACE_ID, sessionId);
    }
}

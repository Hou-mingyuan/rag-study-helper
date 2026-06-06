package com.rag.demo.service;

import com.rag.demo.model.ChatMessage;

import java.util.List;

/**
 * 对话历史存储接口。当前使用 Redis 实现。
 */
public interface ConversationStore {

    List<ChatMessage> getHistory(String sessionId);

    void addTurn(String sessionId, String userMessage, String assistantMessage);

    void clear(String sessionId);
}

package com.rag.studyhelper.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.studyhelper.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 会话存储服务
 */
@Service
public class RedisConversationStore implements ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(RedisConversationStore.class);
    // 最大轮次 超出删除（用户输入 + LLM输出 为一轮）
    private static final int MAX_ROUNDS = 10;
    // 一小时超时时间
    private static final int TTL_HOURS = 1;
    private static final String KEY_PREFIX = "session:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisConversationStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 获取上下文
     */
    @Override
    public List<ChatMessage> getHistory(String sessionId) {
        String json = redisTemplate.opsForValue().get(KEY_PREFIX + sessionId);
        if (json == null) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ChatMessage>>() {});
        } catch (Exception e) {
            log.warn("Failed to deserialize history for session {}", sessionId, e);
            return Collections.emptyList();
        }
    }

    /**
     * 添加轮次
     */
    @Override
    public void addTurn(String sessionId, String userMessage, String assistantMessage) {
        List<ChatMessage> history = getHistory(sessionId);
        if (history.isEmpty()) {
            history = Collections.synchronizedList(new ArrayList<>());
        }
        history.add(new ChatMessage("user", userMessage));
        history.add(new ChatMessage("assistant", assistantMessage));
        int maxMessages = MAX_ROUNDS * 2;
        // 删除多余的轮次
        while (history.size() > maxMessages) {
            history.remove(0);
        }
        try {
            String json = objectMapper.writeValueAsString(history);
            redisTemplate.opsForValue().set(KEY_PREFIX + sessionId, json, TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("Failed to save history for session {}", sessionId, e);
        }
    }

    /**
     * 清空上下文历史
     */
    @Override
    public void clear(String sessionId) {
        redisTemplate.delete(KEY_PREFIX + sessionId);
    }
}

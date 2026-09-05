package com.rag.studyhelper.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.studyhelper.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class RedisConversationStore implements ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(RedisConversationStore.class);
    private static final int MAX_MESSAGES = 20;
    private static final int TTL_SECONDS = 3600;
    private static final String KEY_PREFIX = "rag:space:";
    private static final DefaultRedisScript<Long> APPEND_SCRIPT = appendScript();

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisConversationStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ChatMessage> getHistory(long spaceId, String sessionId) {
        String key = key(spaceId, sessionId);
        List<String> values = redis.opsForList().range(key, 0, -1);
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<ChatMessage> history = new ArrayList<>(values.size());
        for (String value : values) {
            try {
                history.add(objectMapper.readValue(value, ChatMessage.class));
            } catch (Exception error) {
                log.warn("Ignoring malformed conversation message: spaceId={}, sessionId={}",
                        spaceId, sessionId);
            }
        }
        return List.copyOf(history);
    }

    @Override
    public void addTurn(long spaceId, String sessionId,
                        String userMessage, String assistantMessage) {
        try {
            String user = objectMapper.writeValueAsString(new ChatMessage("user", userMessage));
            String assistant = objectMapper.writeValueAsString(
                    new ChatMessage("assistant", assistantMessage));
            redis.execute(APPEND_SCRIPT, List.of(key(spaceId, sessionId)),
                    user, assistant, String.valueOf(MAX_MESSAGES), String.valueOf(TTL_SECONDS));
        } catch (Exception error) {
            throw new IllegalStateException("Conversation history could not be saved", error);
        }
    }

    @Override
    public void clear(long spaceId, String sessionId) {
        redis.delete(key(spaceId, sessionId));
    }

    private String key(long spaceId, String sessionId) {
        if (spaceId <= 0) {
            throw new IllegalArgumentException("Knowledge space id must be positive");
        }
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid session id");
        }
        return KEY_PREFIX + spaceId + ":session:" + sessionId + ":messages";
    }

    private static DefaultRedisScript<Long> appendScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
                redis.call('RPUSH', KEYS[1], ARGV[1], ARGV[2])
                redis.call('LTRIM', KEYS[1], -tonumber(ARGV[3]), -1)
                redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
                return redis.call('LLEN', KEYS[1])
                """);
        return script;
    }
}

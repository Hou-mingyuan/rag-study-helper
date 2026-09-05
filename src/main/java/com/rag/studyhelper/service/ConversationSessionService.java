package com.rag.studyhelper.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rag.studyhelper.mapper.ChatSessionMapper;
import com.rag.studyhelper.model.ChatSession;
import com.rag.studyhelper.model.ChatSessionDetail;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class ConversationSessionService {

    private final ChatSessionMapper sessionsMapper;
    private final ConversationStore conversationStore;
    private final KnowledgeSpaceService spaces;

    public ConversationSessionService(ChatSessionMapper sessionsMapper,
                                      ConversationStore conversationStore,
                                      KnowledgeSpaceService spaces) {
        this.sessionsMapper = sessionsMapper;
        this.conversationStore = conversationStore;
        this.spaces = spaces;
    }

    public List<ChatSession> list(long spaceId) {
        spaces.requireActive(spaceId);
        return sessionsMapper.selectList(Wrappers.<ChatSession>lambdaQuery()
                .eq(ChatSession::getSpaceId, spaceId)
                .eq(ChatSession::getStatus, "ACTIVE")
                .orderByDesc(ChatSession::getUpdateTime)
                .last("LIMIT 100"));
    }

    public ChatSessionDetail get(long spaceId, String sessionId) {
        ChatSession session = require(spaceId, sessionId);
        return new ChatSessionDetail(session, conversationStore.getHistory(spaceId, sessionId));
    }

    @Transactional
    public ChatSession create(long spaceId, String title) {
        spaces.requireActive(spaceId);
        ChatSession session = new ChatSession();
        session.setId(UUID.randomUUID().toString());
        session.setSpaceId(spaceId);
        session.setTitle(normalizeTitle(title));
        session.setStatus("ACTIVE");
        sessionsMapper.insert(session);
        return session;
    }

    @Transactional
    public ChatSession ensure(long spaceId, String sessionId, String firstQuestion) {
        validateSessionId(sessionId);
        ChatSession existing = sessionsMapper.selectById(sessionId);
        if (existing != null) {
            if (!Long.valueOf(spaceId).equals(existing.getSpaceId())
                    || !"ACTIVE".equals(existing.getStatus())) {
                throw new IllegalArgumentException("Session does not belong to this knowledge space");
            }
            return existing;
        }
        spaces.requireActive(spaceId);
        ChatSession session = new ChatSession();
        session.setId(sessionId);
        session.setSpaceId(spaceId);
        session.setTitle(normalizeTitle(firstQuestion));
        session.setStatus("ACTIVE");
        try {
            sessionsMapper.insert(session);
            return session;
        } catch (DuplicateKeyException race) {
            return require(spaceId, sessionId);
        }
    }

    @Transactional
    public ChatSession rename(long spaceId, String sessionId, String title) {
        ChatSession session = require(spaceId, sessionId);
        session.setTitle(normalizeTitle(title));
        sessionsMapper.updateById(session);
        return session;
    }

    public void touch(long spaceId, String sessionId) {
        LocalDateTime touchedAt = LocalDateTime.now();
        sessionsMapper.update(null, Wrappers.<ChatSession>lambdaUpdate()
                .eq(ChatSession::getId, sessionId)
                .eq(ChatSession::getSpaceId, spaceId)
                .set(ChatSession::getLastMessageAt, touchedAt)
                .set(ChatSession::getUpdateTime, touchedAt));
    }

    @Transactional
    public void delete(long spaceId, String sessionId) {
        require(spaceId, sessionId);
        conversationStore.clear(spaceId, sessionId);
        sessionsMapper.delete(Wrappers.<ChatSession>lambdaQuery()
                .eq(ChatSession::getId, sessionId)
                .eq(ChatSession::getSpaceId, spaceId));
    }

    private ChatSession require(long spaceId, String sessionId) {
        validateSessionId(sessionId);
        ChatSession session = sessionsMapper.selectById(sessionId);
        if (session == null || !Long.valueOf(spaceId).equals(session.getSpaceId())
                || !"ACTIVE".equals(session.getStatus())) {
            throw new IllegalArgumentException("Session does not exist in this knowledge space");
        }
        return session;
    }

    private String normalizeTitle(String title) {
        String value = title == null || title.isBlank() ? "New conversation" : title.trim();
        return value.length() <= 160 ? value : value.substring(0, 160);
    }

    private void validateSessionId(String sessionId) {
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid session id");
        }
    }
}

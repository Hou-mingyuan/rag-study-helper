package com.rag.studyhelper.service;

import com.rag.studyhelper.mapper.ChatSessionMapper;
import com.rag.studyhelper.model.ChatMessage;
import com.rag.studyhelper.model.ChatSession;
import com.rag.studyhelper.support.MybatisMetadata;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationSessionServiceTest {

    @Mock
    private ChatSessionMapper mapper;
    @Mock
    private ConversationStore conversations;
    @Mock
    private KnowledgeSpaceService spaces;

    private ConversationSessionService service;

    @BeforeAll
    static void metadata() {
        MybatisMetadata.initialize(ChatSession.class);
    }

    @BeforeEach
    void setUp() {
        service = new ConversationSessionService(mapper, conversations, spaces);
    }

    @Test
    void createsListsAndReadsServerSideHistory() {
        ChatSession listed = session("s-list", 2L, "列表项", "ACTIVE");
        when(mapper.selectList(any())).thenReturn(List.of(listed));
        when(mapper.selectById("s-list")).thenReturn(listed);
        when(conversations.getHistory(2L, "s-list"))
                .thenReturn(List.of(message("user", "问题"), message("assistant", "回答")));

        assertEquals(List.of(listed), service.list(2L));
        assertEquals(2, service.get(2L, "s-list").messages().size());

        ChatSession created = service.create(2L, "  新会话  ");
        assertNotNull(created.getId());
        assertEquals("新会话", created.getTitle());
        assertEquals("ACTIVE", created.getStatus());
        verify(mapper).insert(created);
    }

    @Test
    void ensureReturnsExistingOrCreatesStableClientId() {
        ChatSession existing = session("client-1", 2L, "已有", "ACTIVE");
        when(mapper.selectById("client-1")).thenReturn(existing);
        assertSame(existing, service.ensure(2L, "client-1", "忽略"));

        when(mapper.selectById("client-2")).thenReturn(null);
        ChatSession created = service.ensure(2L, "client-2", "  第一条问题  ");
        assertEquals("client-2", created.getId());
        assertEquals("第一条问题", created.getTitle());
        verify(mapper).insert(created);
    }

    @Test
    void ensureRejectsCrossSpaceAndRecoversInsertRace() {
        when(mapper.selectById("wrong-space"))
                .thenReturn(session("wrong-space", 7L, "x", "ACTIVE"));
        assertThrows(IllegalArgumentException.class,
                () -> service.ensure(2L, "wrong-space", "问题"));

        ChatSession winner = session("race-id", 2L, "并发胜者", "ACTIVE");
        when(mapper.selectById("race-id")).thenReturn(null, winner);
        doThrow(new DuplicateKeyException("race")).when(mapper).insert(any(ChatSession.class));
        assertSame(winner, service.ensure(2L, "race-id", "问题"));
    }

    @Test
    void renameTouchAndDeleteAreScopedToSpace() {
        ChatSession current = session("session-9", 2L, "旧名称", "ACTIVE");
        when(mapper.selectById("session-9")).thenReturn(current);

        assertEquals("New conversation", service.rename(2L, "session-9", " ").getTitle());
        service.touch(2L, "session-9");
        service.delete(2L, "session-9");

        verify(mapper).updateById(current);
        verify(mapper).update(any(), any());
        verify(conversations).clear(2L, "session-9");
        verify(mapper).delete(any());
    }

    @Test
    void validatesSessionIdsOwnershipAndTitleLength() {
        assertThrows(IllegalArgumentException.class, () -> service.get(2L, "bad id"));
        assertThrows(IllegalArgumentException.class, () -> service.ensure(2L, null, "x"));
        when(mapper.selectById("missing")).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.get(2L, "missing"));

        ChatSession inactive = session("inactive", 2L, "x", "DELETED");
        when(mapper.selectById("inactive")).thenReturn(inactive);
        assertThrows(IllegalArgumentException.class, () -> service.get(2L, "inactive"));

        String longTitle = "题".repeat(200);
        ChatSession created = service.create(2L, longTitle);
        assertEquals(160, created.getTitle().length());
        verify(spaces, never()).requireActive(99L);
    }

    private ChatSession session(String id, long spaceId, String title, String status) {
        ChatSession value = new ChatSession();
        value.setId(id);
        value.setSpaceId(spaceId);
        value.setTitle(title);
        value.setStatus(status);
        return value;
    }

    private ChatMessage message(String role, String content) {
        ChatMessage value = new ChatMessage();
        value.setRole(role);
        value.setContent(content);
        return value;
    }
}

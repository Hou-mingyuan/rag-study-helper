package com.rag.studyhelper.controller;

import com.rag.studyhelper.model.ChatSession;
import com.rag.studyhelper.model.ChatSessionDetail;
import com.rag.studyhelper.model.ChatSessionRequest;
import com.rag.studyhelper.service.ConversationSessionService;
import com.rag.studyhelper.utils.Results;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/spaces/{spaceId}/sessions")
public class ChatSessionController {

    private final ConversationSessionService sessions;

    public ChatSessionController(ConversationSessionService sessions) {
        this.sessions = sessions;
    }

    @GetMapping
    public Results<List<ChatSession>> list(@PathVariable long spaceId) {
        return Results.success(sessions.list(spaceId));
    }

    @PostMapping
    public Results<ChatSession> create(@PathVariable long spaceId,
                                       @Valid @RequestBody ChatSessionRequest request) {
        return Results.success(sessions.create(spaceId, request.title()));
    }

    @GetMapping("/{sessionId}")
    public Results<ChatSessionDetail> get(@PathVariable long spaceId,
                                          @PathVariable String sessionId) {
        return Results.success(sessions.get(spaceId, sessionId));
    }

    @PutMapping("/{sessionId}")
    public Results<ChatSession> rename(@PathVariable long spaceId,
                                       @PathVariable String sessionId,
                                       @Valid @RequestBody ChatSessionRequest request) {
        return Results.success(sessions.rename(spaceId, sessionId, request.title()));
    }

    @DeleteMapping("/{sessionId}")
    public Results<Void> delete(@PathVariable long spaceId, @PathVariable String sessionId) {
        sessions.delete(spaceId, sessionId);
        return Results.success("Session deleted");
    }
}

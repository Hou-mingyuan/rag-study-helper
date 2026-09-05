package com.rag.studyhelper.model;

import java.util.List;

public record ChatSessionDetail(ChatSession session, List<ChatMessage> messages) {
}

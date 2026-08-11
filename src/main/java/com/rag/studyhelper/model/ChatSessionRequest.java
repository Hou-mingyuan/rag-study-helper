package com.rag.studyhelper.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatSessionRequest(@NotBlank @Size(max = 160) String title) {
}

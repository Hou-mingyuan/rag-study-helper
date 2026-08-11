package com.rag.studyhelper.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KnowledgeSpaceRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String description) {
}

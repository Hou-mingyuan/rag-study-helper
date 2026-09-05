package com.rag.studyhelper.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 聊天消息
 */
@Data
@Accessors(chain = true)
@AllArgsConstructor
@NoArgsConstructor
public class ChatRequest {

    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9_-]{1,64}")
    private String sessionId;

    @NotBlank
    @Size(max = 2000)
    private String question;
}

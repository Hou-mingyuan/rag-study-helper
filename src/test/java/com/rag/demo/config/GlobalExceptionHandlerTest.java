package com.rag.demo.config;

import com.rag.demo.utils.Results;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(ExceptionTestController.class)
@Import(GlobalExceptionHandler.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldReturn400ForIllegalArgument() throws Exception {
        mockMvc.perform(get("/test/illegal-argument"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resCode").value("400"))
                .andExpect(jsonPath("$.msg").value("参数错误"));
    }

    @Test
    void shouldReturn404ForNoSuchElement() throws Exception {
        mockMvc.perform(get("/test/no-such-element"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resCode").value("404"))
                .andExpect(jsonPath("$.msg").value("资源不存在"));
    }

    @Test
    void shouldReturn500ForUnknownException() throws Exception {
        mockMvc.perform(get("/test/unknown-error"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resCode").value("500"))
                .andExpect(jsonPath("$.msg").value("服务器内部错误"));
    }
}

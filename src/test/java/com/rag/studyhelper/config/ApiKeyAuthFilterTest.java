package com.rag.studyhelper.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyAuthFilterTest {

    private final ApiKeyProperties properties = new ApiKeyProperties();
    private final ApiKeyAuthFilter filter = new ApiKeyAuthFilter();

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(filter, "apiKeyProperties", properties);
        properties.setEnabled(true);
        properties.setValue("test-secret-key");
        properties.setHeader("X-API-Key");
    }

    @Test
    void allowsHealthWithoutKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertTrue(chain.getRequest() instanceof MockHttpServletRequest);
    }

    @Test
    void rejectsProtectedApiWithoutKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("401"));
    }

    @Test
    void allowsProtectedApiWithValidKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat");
        request.addHeader("X-API-Key", "test-secret-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertTrue(chain.getRequest() instanceof MockHttpServletRequest);
    }

    @Test
    void disabledWhenNotConfigured() throws Exception {
        properties.setEnabled(false);
        properties.setValue("");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
    }
}

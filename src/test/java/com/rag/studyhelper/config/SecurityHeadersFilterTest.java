package com.rag.studyhelper.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityHeadersFilterTest {

    @Test
    void addsBrowserSecurityHeadersAndContinuesTheChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new SecurityHeadersFilter().doFilter(request, response, chain);

        assertTrue(response.getHeader("Content-Security-Policy").contains("frame-ancestors 'none'"));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals("DENY", response.getHeader("X-Frame-Options"));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
        assertEquals("camera=(), microphone=(), geolocation=()",
                response.getHeader("Permissions-Policy"));
        assertEquals("same-origin", response.getHeader("Cross-Origin-Opener-Policy"));
        assertEquals(request, chain.getRequest());
    }

    @Test
    void preventsCachingMutableApiResponses() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SecurityHeadersFilter().doFilter(request, response, new MockFilterChain());

        assertEquals("no-store", response.getHeader("Cache-Control"));
    }
}

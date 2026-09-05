package com.rag.studyhelper.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.studyhelper.service.RateLimitService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitAspectTest {

    @Mock
    private RateLimitService limits;
    @Mock
    private ProceedingJoinPoint joinPoint;
    @Mock
    private IpRateLimit annotation;

    private RateLimitAspect aspect;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        aspect = new RateLimitAspect(limits, new ObjectMapper());
        ReflectionTestUtils.setField(aspect, "ipRate", 20);
        ReflectionTestUtils.setField(aspect, "dailyMax", 100);
        ReflectionTestUtils.setField(aspect, "trustForwardedFor", false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.9");
        request.addHeader("X-Forwarded-For", "203.0.113.8");
        response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
        when(annotation.value()).thenReturn("chat");
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void proceedsWhenBothLimitsAllowAndDoesNotTrustForwardedHeaderByDefault() throws Throwable {
        when(limits.tryAcquire("ip:chat:127.0.0.9", 20)).thenReturn(true);
        when(limits.tryDaily("chat", 100)).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("ok");

        assertEquals("ok", aspect.chatAround(joinPoint, annotation));
        verify(limits).tryAcquire("ip:chat:127.0.0.9", 20);
    }

    @Test
    void rejectedRequestUsesHttp429AndRetryAfter() throws Throwable {
        when(limits.tryAcquire("ip:chat:127.0.0.9", 20)).thenReturn(false);

        assertNull(aspect.chatAround(joinPoint, annotation));

        assertEquals(429, response.getStatus());
        assertEquals("60", response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("429"));
    }

    @Test
    void redisFailureFailsClosedWithHttp503() throws Throwable {
        when(limits.tryAcquire("ip:chat:127.0.0.9", 20))
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertNull(aspect.chatAround(joinPoint, annotation));

        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("503"));
    }
}

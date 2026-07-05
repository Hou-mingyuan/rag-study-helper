package com.rag.studyhelper.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ResultsTest {

    @Test
    void successWrapsPayload() {
        Results<Integer> result = Results.success(1);

        assertEquals("200", result.getResCode());
        assertEquals("成功", result.getMsg());
        assertEquals(1, result.getObj());
    }

    @Test
    void failedWrapsCustomCodeAndMessage() {
        Results<Void> result = Results.failed("429", "请求过于频繁");

        assertEquals("429", result.getResCode());
        assertEquals("请求过于频繁", result.getMsg());
        assertNull(result.getObj());
    }
}

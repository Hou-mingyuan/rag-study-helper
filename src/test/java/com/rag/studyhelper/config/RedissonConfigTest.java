package com.rag.studyhelper.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RedissonConfigTest {

    @Test
    void usesSameLogicalDatabaseAsSpringDataRedis() {
        RedissonConfig configuration = new RedissonConfig();
        ReflectionTestUtils.setField(configuration, "host", "infra-redis");
        ReflectionTestUtils.setField(configuration, "port", 6379);
        ReflectionTestUtils.setField(configuration, "password", "");
        ReflectionTestUtils.setField(configuration, "database", 3);

        var server = configuration.createConfig().useSingleServer();

        assertEquals("redis://infra-redis:6379", server.getAddress());
        assertEquals(3, server.getDatabase());
        assertNull(server.getPassword());
    }
}

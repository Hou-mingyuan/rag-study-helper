package com.rag.studyhelper.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagProviderResolverTest {

    @Test
    void mockProvider_forcesMockMode() {
        RagProviderResolver resolver = new RagProviderResolver("mock", "", "");
        assertTrue(resolver.isMockMode());
    }

    @Test
    void autoProvider_mockWhenKeysMissing() {
        RagProviderResolver resolver = new RagProviderResolver("auto", "", "");
        assertTrue(resolver.isMockMode());
    }

    @Test
    void openaiProvider_disablesMockMode() {
        RagProviderResolver resolver = new RagProviderResolver("openai", "chat-key", "embed-key");
        assertFalse(resolver.isMockMode());
    }
}

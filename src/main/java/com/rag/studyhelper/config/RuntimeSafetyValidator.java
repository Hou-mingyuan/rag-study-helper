package com.rag.studyhelper.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

@Component
public class RuntimeSafetyValidator implements ApplicationRunner {

    private final ApiKeyProperties apiKey;
    private final String serverAddress;
    private final boolean publicAccessEnabled;
    private final boolean hostPublishedLoopback;

    public RuntimeSafetyValidator(
            ApiKeyProperties apiKey,
            @Value("${server.address:127.0.0.1}") String serverAddress,
            @Value("${app.network.public-access-enabled:false}") boolean publicAccessEnabled,
            @Value("${app.network.host-published-loopback:false}") boolean hostPublishedLoopback) {
        this.apiKey = apiKey;
        this.serverAddress = serverAddress;
        this.publicAccessEnabled = publicAccessEnabled;
        this.hostPublishedLoopback = hostPublishedLoopback;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (apiKey.isEnabled() && !apiKey.isConfigured()) {
            throw new IllegalStateException("API key authentication is enabled but APP_API_KEY is empty");
        }
        if (apiKey.getHeader() == null
                || !apiKey.getHeader().matches("[A-Za-z0-9-]{1,64}")) {
            throw new IllegalStateException("API key header name is invalid");
        }

        boolean loopback = InetAddress.getByName(serverAddress).isLoopbackAddress();
        if (!loopback && !publicAccessEnabled && !hostPublishedLoopback) {
            throw new IllegalStateException(
                    "Non-loopback binding requires explicit public access or loopback-only host publishing");
        }
        if (publicAccessEnabled && !apiKey.isConfigured()) {
            throw new IllegalStateException("Public access requires API key authentication");
        }
    }
}

package com.rag.studyhelper.service;

import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ChatRequestRegistry {

    private final ConcurrentHashMap<String, ActiveRequest> active = new ConcurrentHashMap<>();

    public String begin(long spaceId) {
        String requestId = UUID.randomUUID().toString();
        active.put(requestId, new ActiveRequest(spaceId, new AtomicBoolean()));
        return requestId;
    }

    public boolean cancel(long spaceId, String requestId) {
        ActiveRequest request = active.get(requestId);
        return request != null && request.spaceId == spaceId
                && request.cancelled.compareAndSet(false, true);
    }

    public boolean isCancelled(String requestId) {
        ActiveRequest request = active.get(requestId);
        return request == null || request.cancelled.get();
    }

    public void complete(String requestId) {
        active.remove(requestId);
    }

    private record ActiveRequest(long spaceId, AtomicBoolean cancelled) {
    }
}

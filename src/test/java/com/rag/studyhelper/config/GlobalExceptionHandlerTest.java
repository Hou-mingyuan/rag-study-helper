package com.rag.studyhelper.config;

import com.rag.studyhelper.utils.Results;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsCommonHttpContractErrorsWithoutReturningInternalServerError() {
        assertStatus(HttpStatus.NOT_ACCEPTABLE, handler.handleNotAcceptable());
        assertStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE, handler.handleUnsupportedMediaType());
        assertStatus(HttpStatus.METHOD_NOT_ALLOWED, handler.handleMethodNotAllowed());
        assertStatus(HttpStatus.BAD_REQUEST, handler.handleMalformedRequest());
    }

    private void assertStatus(HttpStatus expected, ResponseEntity<Results<Void>> response) {
        assertEquals(expected, response.getStatusCode());
        assertEquals(String.valueOf(expected.value()), response.getBody().getResCode());
    }
}

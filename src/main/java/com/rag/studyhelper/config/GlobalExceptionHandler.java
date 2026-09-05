package com.rag.studyhelper.config;

import com.rag.studyhelper.utils.Results;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.concurrent.CancellationException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({IllegalArgumentException.class, ConstraintViolationException.class})
    public ResponseEntity<Results<Void>> handleBadRequest(Exception error) {
        return response(HttpStatus.BAD_REQUEST, error.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Results<Void>> handleValidation(MethodArgumentNotValidException error) {
        String message = error.getBindingResult().getFieldErrors().isEmpty()
                ? "Request validation failed"
                : error.getBindingResult().getFieldErrors().get(0).getField() + " is invalid";
        return response(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler({NoSuchElementException.class, NoResourceFoundException.class})
    public ResponseEntity<Results<Void>> handleNotFound(Exception error) {
        return response(HttpStatus.NOT_FOUND, error.getMessage());
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Results<Void>> handleNotAcceptable() {
        return response(HttpStatus.NOT_ACCEPTABLE, "Requested response type is not supported");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Results<Void>> handleUnsupportedMediaType() {
        return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Request content type is not supported");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Results<Void>> handleMethodNotAllowed() {
        return response(HttpStatus.METHOD_NOT_ALLOWED, "Request method is not supported");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<Results<Void>> handleMalformedRequest() {
        return response(HttpStatus.BAD_REQUEST, "Request body or parameters are invalid");
    }

    @ExceptionHandler({IllegalStateException.class, DuplicateKeyException.class})
    public ResponseEntity<Results<Void>> handleConflict(Exception error) {
        return response(HttpStatus.CONFLICT, error.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Results<Void>> handleUploadTooLarge() {
        return response(HttpStatus.PAYLOAD_TOO_LARGE, "Upload exceeds the configured size limit");
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Results<Void>> handleIo(IOException error) {
        log.warn("Request dependency or document I/O failed: type={}",
                error.getClass().getSimpleName());
        return response(HttpStatus.UNPROCESSABLE_ENTITY, "The document or dependency could not be processed");
    }

    @ExceptionHandler(CancellationException.class)
    public ResponseEntity<Results<Void>> handleCancelled() {
        return response(HttpStatus.CONFLICT, "The operation was cancelled");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Results<Void>> handleException(Exception error) {
        log.error("Unhandled request exception: type={}", error.getClass().getSimpleName(), error);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    private ResponseEntity<Results<Void>> response(HttpStatus status, String message) {
        String safeMessage = message == null || message.isBlank() ? status.getReasonPhrase() : message;
        return ResponseEntity.status(status)
                .body(Results.failed(String.valueOf(status.value()), safeMessage));
    }
}

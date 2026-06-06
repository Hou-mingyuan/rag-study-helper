package com.rag.studyhelper.config;

import com.rag.studyhelper.utils.Results;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public Results<Void> handleIllegalArgument(IllegalArgumentException e) {
        return Results.failed("400", e.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public Results<Void> handleNotFound(NoSuchElementException e) {
        return Results.failed("404", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Results<Void> handleException(Exception e) {
        log.error("Unhandled exception", e);
        return Results.failed("500", "服务器内部错误");
    }
}

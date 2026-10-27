package com.moa.server.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;

@RestControllerAdvice
public class ApiErrors {
    public record ErrorResponse(String message) { }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> domain(ApiException error) {
        var response = ResponseEntity.status(error.status());
        if (error.status() == HttpStatus.TOO_MANY_REQUESTS) response.header("Retry-After", "60");
        return response.body(new ErrorResponse(error.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> size() {
        return ResponseEntity.status(413).body(new ErrorResponse("사진은 한 장에 10MB까지 올릴 수 있어요."));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MissingServletRequestPartException.class, MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> invalid() {
        return ResponseEntity.badRequest().body(new ErrorResponse("입력한 내용을 확인해 주세요."));
    }
}

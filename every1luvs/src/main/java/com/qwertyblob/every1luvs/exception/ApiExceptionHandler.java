package com.qwertyblob.every1luvs.exception;

import com.qwertyblob.every1luvs.dto.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatusException(ResponseStatusException exception) {
        String code = exception instanceof CodedResponseStatusException coded ? coded.getCode() : null;
        return ResponseEntity
                .status(exception.getStatusCode())
                .body(new ErrorResponse(exception.getReason(), code));
    }
}

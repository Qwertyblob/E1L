package com.qwertyblob.every1luvs.exception;

import com.qwertyblob.every1luvs.dto.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void handleResponseStatusException_mapsStatusAndReason() {
        ResponseStatusException exception =
                new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered.");

        ResponseEntity<ErrorResponse> response = handler.handleResponseStatusException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Email already registered.");
    }

    @Test
    void handleResponseStatusException_nullReason_returnsNullMessage() {
        ResponseStatusException exception = new ResponseStatusException(HttpStatus.NOT_FOUND);

        ResponseEntity<ErrorResponse> response = handler.handleResponseStatusException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isNull();
    }

    @Test
    void handleResponseStatusException_preservesUnauthorizedStatus() {
        ResponseStatusException exception =
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");

        ResponseEntity<ErrorResponse> response = handler.handleResponseStatusException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().message()).isEqualTo("Authentication is required.");
    }
}

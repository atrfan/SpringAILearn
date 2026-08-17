package com.foxmimi.springaichat.handler;

import com.foxmimi.springaichat.model.response.ErrorResponse;
import com.foxmimi.springaichat.tool.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerAuthorizationTest {

    @Test
    void mapsUnauthorizedExceptionToStructured401Response() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ErrorResponse> response = handler.handleUnauthorized(
                new UnauthorizedException("Invalid or missing API key")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("UNAUTHORIZED");
        assertThat(response.getBody().message()).isEqualTo("Invalid or missing API key");
        assertThat(response.getBody().timestamp()).isPositive();
    }
}

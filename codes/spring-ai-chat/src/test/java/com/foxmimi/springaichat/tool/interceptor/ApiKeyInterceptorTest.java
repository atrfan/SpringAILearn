package com.foxmimi.springaichat.tool.interceptor;

import com.foxmimi.springaichat.tool.config.ApiKeyProperties;
import com.foxmimi.springaichat.tool.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiKeyInterceptorTest {

    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final Object handler = new Object();

    @Test
    void acceptsConfiguredApiKey() {
        ApiKeyInterceptor interceptor = interceptorWithKeys("valid-key");
        when(request.getHeader("X-API-Key")).thenReturn("valid-key");

        boolean accepted = interceptor.preHandle(request, response, handler);

        assertThat(accepted).isTrue();
    }

    @Test
    void rejectsMissingApiKey() {
        ApiKeyInterceptor interceptor = interceptorWithKeys("valid-key");
        when(request.getHeader("X-API-Key")).thenReturn(null);

        assertUnauthorized(interceptor);
    }

    @Test
    void rejectsBlankApiKey() {
        ApiKeyInterceptor interceptor = interceptorWithKeys("valid-key");
        when(request.getHeader("X-API-Key")).thenReturn("");

        assertUnauthorized(interceptor);
    }

    @Test
    void rejectsUnknownApiKey() {
        ApiKeyInterceptor interceptor = interceptorWithKeys("valid-key");
        when(request.getHeader("X-API-Key")).thenReturn("wrong-key");

        assertUnauthorized(interceptor);
    }

    @Test
    void rejectsEveryRequestWhenWhitelistIsEmpty() {
        ApiKeyInterceptor interceptor = interceptorWithKeys();
        when(request.getHeader("X-API-Key")).thenReturn("any-key");

        assertUnauthorized(interceptor);
    }

    private ApiKeyInterceptor interceptorWithKeys(String... keys) {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.setApiKeys(List.of(keys));
        return new ApiKeyInterceptor(properties);
    }

    private void assertUnauthorized(ApiKeyInterceptor interceptor) {
        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid or missing API key");
    }
}

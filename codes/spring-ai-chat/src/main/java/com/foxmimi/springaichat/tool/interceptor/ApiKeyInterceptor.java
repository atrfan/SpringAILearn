package com.foxmimi.springaichat.tool.interceptor;

import com.foxmimi.springaichat.tool.config.ApiKeyProperties;
import com.foxmimi.springaichat.tool.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ApiKeyInterceptor implements HandlerInterceptor {

    private final ApiKeyProperties apiKeyProperties;

    public ApiKeyInterceptor(ApiKeyProperties apiKeyProperties) {
        this.apiKeyProperties = apiKeyProperties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String key = request.getHeader("X-API-Key");
        if (key == null || key.isEmpty() || !apiKeyProperties.getApiKeys().contains(key)) {
            throw new UnauthorizedException("Invalid or missing API key");
        }
        return true;
    }
}

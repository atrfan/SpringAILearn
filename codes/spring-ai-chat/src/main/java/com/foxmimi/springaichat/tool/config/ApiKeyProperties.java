package com.foxmimi.springaichat.tool.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties("security")
@Component
public class ApiKeyProperties {

    private List<String> apiKeys = new ArrayList<>();

    public List<String> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(List<String> apiKeys) {
        this.apiKeys = new ArrayList<>();
        if (apiKeys == null) {
            return;
        }
        for (String key : apiKeys) {
            if (key == null) {
                continue;
            }
            String trimmed = key.trim();
            if (!trimmed.isEmpty()) {
                this.apiKeys.add(trimmed);
            }
        }
    }
}

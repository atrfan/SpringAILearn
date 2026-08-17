package com.foxmimi.springaichat.tool.config;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyPropertiesTest {

    @Test
    void defaultsToEmptyApiKeyList() {
        ApiKeyProperties properties = new ApiKeyProperties();

        assertThat(properties.getApiKeys()).isEmpty();
    }

    @Test
    void trimsKeysAndRemovesNullOrBlankValues() {
        ApiKeyProperties properties = new ApiKeyProperties();

        properties.setApiKeys(Arrays.asList(" first-key ", null, "", "   ", "second-key"));

        assertThat(properties.getApiKeys()).containsExactly("first-key", "second-key");
    }

    @Test
    void nullConfigurationProducesEmptyApiKeyList() {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.setApiKeys(List.of("configured-key"));

        properties.setApiKeys(null);

        assertThat(properties.getApiKeys()).isEmpty();
    }
}

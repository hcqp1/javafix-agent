package com.javafix.agent.config;

import java.util.Objects;

/** Model endpoint settings. The API key is deliberately omitted from {@link #toString()}. */
public record ModelConfig(String baseUrl, String apiKey, String model) {

    public ModelConfig {
        baseUrl = requireText(baseUrl, "baseUrl");
        apiKey = requireText(apiKey, "apiKey");
        model = requireText(model, "model");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    @Override
    public String toString() {
        return "ModelConfig[baseUrl=" + baseUrl + ", apiKey=<redacted>, model=" + model + "]";
    }
}

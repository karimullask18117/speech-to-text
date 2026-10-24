package com.example.stt.ws;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the Gemini Live API (bound from "gemini.*"). */
@ConfigurationProperties(prefix = "gemini")
public record GeminiProperties(String apiKey, String model, String endpoint, long finalizeDelayMs) {
}

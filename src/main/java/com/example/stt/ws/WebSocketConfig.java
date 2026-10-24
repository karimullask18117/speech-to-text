package com.example.stt.ws;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
@EnableConfigurationProperties(GeminiProperties.class)
public class WebSocketConfig implements WebSocketConfigurer {

    private final TranscribeSocketHandler handler;

    public WebSocketConfig(TranscribeSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Same-origin in production (the UI is served by this app); "*" keeps local dev simple.
        registry.addHandler(handler, "/ws/transcribe").setAllowedOriginPatterns("*");
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean c = new ServletServerContainerFactoryBean();
        c.setMaxBinaryMessageBufferSize(256 * 1024);
        c.setMaxTextMessageBufferSize(64 * 1024);
        c.setMaxSessionIdleTimeout(15 * 60 * 1000L);
        return c;
    }
}

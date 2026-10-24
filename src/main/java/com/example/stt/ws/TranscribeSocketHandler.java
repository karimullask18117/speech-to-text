package com.example.stt.ws;

import com.example.stt.model.Transcription;
import com.example.stt.service.TranscriptionService;
import com.example.stt.web.TranscriptionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Browser protocol:
 *   client -> server : binary frames (PCM16 mono 16kHz), text "stop"
 *   server -> client : {"type":"ready"} | {"type":"transcript","text":"..."} |
 *                      {"type":"saved","transcription":{...}} | {"type":"empty"} | {"type":"error","message":"..."}
 */
@Component
public class TranscribeSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TranscribeSocketHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final GeminiProperties props;
    private final TranscriptionService service;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Ctx> sessions = new ConcurrentHashMap<>();

    public TranscribeSocketHandler(GeminiProperties props, TranscriptionService service) {
        this.props = props;
        this.service = service;
    }

    private static final class Ctx {
        final GeminiBridge bridge;
        final long startedAt = System.currentTimeMillis();
        final String language;
        volatile boolean finished;
        Ctx(GeminiBridge bridge, String language) { this.bridge = bridge; this.language = language; }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            send(session, Map.of("type", "error",
                    "message", "GEMINI_API_KEY is not configured on the server."));
            session.close(CloseStatus.SERVER_ERROR);
            return;
        }
        GeminiBridge bridge = new GeminiBridge(props,
                text -> send(session, Map.of("type", "transcript", "text", text)),
                msg -> send(session, Map.of("type", "error", "message", msg)));
        sessions.put(session.getId(), new Ctx(bridge, "Hindi + English (auto)"));
        bridge.connect();
        send(session, Map.of("type", "ready"));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        Ctx c = sessions.get(session.getId());
        if (c == null || c.finished) return;
        java.nio.ByteBuffer buf = message.getPayload();
        byte[] pcm = new byte[buf.remaining()];
        buf.get(pcm);
        c.bridge.sendAudio(pcm);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Ctx c = sessions.get(session.getId());
        if (c == null || c.finished || !"stop".equals(message.getPayload().trim())) return;
        c.bridge.endAudio();
        // Give Gemini a moment to flush the last transcription fragment, then persist.
        scheduler.schedule(() -> finish(session, c, true), props.finalizeDelayMs(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Ctx c = sessions.get(session.getId());
        if (c != null) finish(session, c, false);
    }

    private void finish(WebSocketSession session, Ctx c, boolean notify) {
        synchronized (c) {
            if (c.finished) return;
            c.finished = true;
        }
        sessions.remove(session.getId());
        try {
            String text = c.bridge.transcript();
            if (text.isBlank()) {
                if (notify) send(session, Map.of("type", "empty"));
            } else {
                int secs = (int) ((System.currentTimeMillis() - c.startedAt) / 1000);
                Transcription saved = service.create(new TranscriptionRequest(null, text, c.language, secs));
                if (notify) send(session, Map.of("type", "saved", "transcription", saved));
            }
        } catch (Exception e) {
            log.error("Failed to save transcription", e);
            if (notify) send(session, Map.of("type", "error", "message", "Could not save transcription"));
        } finally {
            c.bridge.close();
            try { if (session.isOpen()) session.close(CloseStatus.NORMAL); } catch (IOException ignored) { }
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private void send(WebSocketSession session, Object payload) {
        try {
            synchronized (session) {
                if (session.isOpen()) session.sendMessage(new TextMessage(MAPPER.writeValueAsString(payload)));
            }
        } catch (Exception e) {
            log.debug("send to browser failed: {}", e.toString());
        }
    }
}

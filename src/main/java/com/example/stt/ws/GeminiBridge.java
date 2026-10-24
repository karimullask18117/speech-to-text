package com.example.stt.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * One upstream connection to the Gemini Live API for one browser session.
 * Audio goes up as base64 PCM16 @16kHz; input-audio transcription fragments come back
 * and are delivered to {@code onText}. Gemini auto-detects language per utterance, which is
 * what makes mixed Hindi/English (code-switching) work without any language hint.
 */
public class GeminiBridge implements WebSocket.Listener {

    private static final Logger log = LoggerFactory.getLogger(GeminiBridge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final GeminiProperties props;
    private final Consumer<String> onText;
    private final Consumer<String> onError;

    private final StringBuilder transcript = new StringBuilder();
    private final List<byte[]> pending = new ArrayList<>();
    private final ByteArrayOutputStream frame = new ByteArrayOutputStream();

    private volatile WebSocket ws;
    private volatile boolean ready;
    private volatile boolean closed;
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);

    public GeminiBridge(GeminiProperties props, Consumer<String> onText, Consumer<String> onError) {
        this.props = props;
        this.onText = onText;
        this.onError = onError;
    }

    public void connect() {
        URI uri = URI.create(props.endpoint() + "?key=" + props.apiKey());
        HTTP.newWebSocketBuilder().buildAsync(uri, this).whenComplete((w, ex) -> {
            if (ex != null) {
                log.warn("Gemini connect failed: {}", ex.toString());
                onError.accept("Could not connect to Gemini: " + rootMessage(ex));
            }
        });
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        this.ws = webSocket;
        ObjectNode setup = MAPPER.createObjectNode();
        ObjectNode s = setup.putObject("setup");
        s.put("model", "models/" + props.model());
        s.putObject("generationConfig").putArray("responseModalities").add("AUDIO");
        s.putObject("inputAudioTranscription");
        s.putObject("systemInstruction").putArray("parts").addObject().put("text",
                "You are a silent transcription engine. Never reply or speak. "
                        + "The user may mix Hindi and English in one sentence.");
        sendText(setup.toString());
        webSocket.request(1);
    }

    /** Called with raw PCM16 mono 16kHz little-endian bytes from the browser. */
    public synchronized void sendAudio(byte[] pcm) {
        if (closed) return;
        if (!ready) {
            pending.add(pcm);
            return;
        }
        sendAudioNow(pcm);
    }

    private void sendAudioNow(byte[] pcm) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode a = root.putObject("realtimeInput").putObject("audio");
        a.put("data", Base64.getEncoder().encodeToString(pcm));
        a.put("mimeType", "audio/pcm;rate=16000");
        sendText(root.toString());
    }

    /** Tell Gemini the mic is off so it flushes the final transcription fragment. */
    public synchronized void endAudio() {
        if (closed) return;
        ObjectNode root = MAPPER.createObjectNode();
        root.putObject("realtimeInput").put("audioStreamEnd", true);
        sendText(root.toString());
    }

    public synchronized String transcript() {
        return transcript.toString().trim();
    }

    public void close() {
        closed = true;
        WebSocket w = ws;
        if (w != null) w.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
    }

    // java.net.http.WebSocket forbids overlapping sends, so chain them.
    private synchronized void sendText(String json) {
        WebSocket w = ws;
        if (w == null) return;
        sendChain = sendChain.thenCompose(x -> w.sendText(json, true)).exceptionally(ex -> {
            log.debug("Gemini send failed: {}", ex.toString());
            return null;
        });
    }

    @Override
    public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
        frame.writeBytes(data.toString().getBytes(StandardCharsets.UTF_8));
        if (last) flushFrame();
        w.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket w, ByteBuffer data, boolean last) {
        byte[] b = new byte[data.remaining()];
        data.get(b);
        frame.writeBytes(b);
        if (last) flushFrame();
        w.request(1);
        return null;
    }

    private void flushFrame() {
        String json = frame.toString(StandardCharsets.UTF_8);
        frame.reset();
        try {
            handle(MAPPER.readTree(json));
        } catch (Exception e) {
            log.debug("Unparseable Gemini frame: {}", e.toString());
        }
    }

    private void handle(JsonNode n) {
        if (n.has("setupComplete")) {
            synchronized (this) {
                ready = true;
                pending.forEach(this::sendAudioNow);
                pending.clear();
            }
            return;
        }
        if (n.has("error")) {
            onError.accept(n.path("error").path("message").asText("Gemini error"));
            return;
        }
        JsonNode frag = n.path("serverContent").path("inputTranscription").path("text");
        if (frag.isTextual() && !frag.asText().isEmpty()) {
            synchronized (this) {
                transcript.append(frag.asText());
            }
            onText.accept(frag.asText());
        }
    }

    @Override
    public CompletionStage<?> onClose(WebSocket w, int statusCode, String reason) {
        if (!closed && statusCode != WebSocket.NORMAL_CLOSURE) {
            onError.accept("Gemini closed the connection (" + statusCode + "): " + reason);
        }
        closed = true;
        return null;
    }

    @Override
    public void onError(WebSocket w, Throwable error) {
        if (!closed) onError.accept("Gemini connection error: " + rootMessage(error));
        closed = true;
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return String.valueOf(t.getMessage());
    }
}

package com.example.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class TranscriptionApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    private long create(String text) throws Exception {
        String body = mvc.perform(post("/api/transcriptions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"text\":\"" + text + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return om.readTree(body).get("id").asLong();
    }

    @Test
    void fullCrudCycleWithHindiEnglishText() throws Exception {
        long id = create("मैं कल office जा रहा हूँ, meeting है");

        mvc.perform(get("/api/transcriptions/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.text", containsString("office")))
                .andExpect(jsonPath("$.text", containsString("मैं")));

        mvc.perform(put("/api/transcriptions/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Edited\",\"text\":\"updated text\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Edited"));

        mvc.perform(get("/api/transcriptions").param("q", "UPDATED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value((int) id));

        mvc.perform(delete("/api/transcriptions/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/transcriptions/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void blankTextIsRejected() throws Exception {
        mvc.perform(post("/api/transcriptions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingTitleGetsDefault() throws Exception {
        mvc.perform(post("/api/transcriptions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hello\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.title", startsWith("Recording")));
    }

    @Test
    void whitespaceQueriesAndNegativeDurationAreNormalized() throws Exception {
        long id = create("hello there");

        mvc.perform(get("/api/transcriptions").param("q", "   hello   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value((int) id));

        mvc.perform(put("/api/transcriptions/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"updated\",\"durationSeconds\":-5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.durationSeconds").value(0));
    }
}

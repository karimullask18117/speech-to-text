package com.example.stt.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TranscriptionRequest(
        @Size(max = 200) String title,
        @NotBlank(message = "text must not be blank") String text,
        @Size(max = 50) String language,
        Integer durationSeconds) {
}

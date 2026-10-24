package com.example.stt.repo;

import com.example.stt.model.Transcription;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TranscriptionRepository extends JpaRepository<Transcription, Long> {
    Page<Transcription> findByTextContainingIgnoreCaseOrTitleContainingIgnoreCase(
            String text, String title, Pageable pageable);
}

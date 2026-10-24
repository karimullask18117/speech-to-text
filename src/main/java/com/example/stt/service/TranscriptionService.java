package com.example.stt.service;

import com.example.stt.model.Transcription;
import com.example.stt.repo.TranscriptionRepository;
import com.example.stt.web.TranscriptionRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.Instant;

@Service
@Transactional
public class TranscriptionService {

    private final TranscriptionRepository repo;

    public TranscriptionService(TranscriptionRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public Page<Transcription> list(String q, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        String query = q == null ? null : q.trim();
        if (query == null || query.isBlank()) return repo.findAll(pr);
        return repo.findByTextContainingIgnoreCaseOrTitleContainingIgnoreCase(query, query, pr);
    }

    @Transactional(readOnly = true)
    public Transcription get(Long id) {
        return repo.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transcription " + id + " not found"));
    }

    public Transcription create(TranscriptionRequest r) {
        Transcription t = new Transcription();
        apply(t, r);
        return repo.save(t);
    }

    public Transcription update(Long id, TranscriptionRequest r) {
        Transcription t = get(id);
        apply(t, r);
        return repo.save(t);
    }

    public void delete(Long id) {
        repo.delete(get(id));
    }

    private void apply(Transcription t, TranscriptionRequest r) {
        String title = r.title() == null ? null : r.title().trim();
        if (title == null || title.isBlank()) {
            title = "Recording " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneOffset.UTC).format(Instant.now()) + " UTC";
        }

        String text = r.text() == null ? null : r.text().trim();
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text must not be blank");
        }

        String language = r.language() == null ? null : r.language().trim();
        if (language != null && language.isBlank()) {
            language = null;
        }

        Integer durationSeconds = r.durationSeconds();
        if (durationSeconds != null && durationSeconds < 0) {
            durationSeconds = 0;
        }

        t.setTitle(title);
        t.setText(text);
        t.setLanguage(language);
        t.setDurationSeconds(durationSeconds);
    }
}

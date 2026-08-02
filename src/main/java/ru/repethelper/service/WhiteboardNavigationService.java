package ru.repethelper.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.repethelper.domain.*;
import ru.repethelper.repository.WhiteboardRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
public class WhiteboardNavigationService {
    private static final int MAX_LIMIT = 20;
    private final WhiteboardRepository boards;
    private final WhiteboardService whiteboardService;

    public WhiteboardNavigationService(WhiteboardRepository boards, WhiteboardService whiteboardService) {
        this.boards = boards;
        this.whiteboardService = whiteboardService;
    }

    @Transactional(readOnly = true)
    public RelatedBoards related(User user, UUID currentPublicId, String cursor, int requestedLimit, Instant now) {
        Whiteboard current = whiteboardService.requireAccessible(user, currentPublicId);
        User teacher = current.getLesson().getTeacher();
        User student = current.getLesson().getStudent();
        Cursor decoded = decode(cursor);
        int limit = Math.max(1, Math.min(MAX_LIMIT, requestedLimit));

        // Fetch extra rows because an already started lesson may not have reached its end yet.
        var pageable = PageRequest.of(0, Math.min(100, limit * 5 + 1));
        List<Whiteboard> candidates = decoded == null
                ? boards.findRelatedActiveBoardsInitial(teacher, student, LessonStatus.CANCELLED, pageable)
                : boards.findRelatedActiveBoardsBefore(teacher, student, LessonStatus.CANCELLED,
                        decoded.startAt(), decoded.id(), pageable);
        List<Whiteboard> completed = candidates.stream()
                .filter(board -> !board.getLesson().getEndAt().isAfter(now))
                .filter(board -> !board.getPublicId().equals(currentPublicId))
                .toList();
        boolean hasMore = completed.size() > limit || candidates.size() > limit * 5;
        List<Whiteboard> page = completed.stream().limit(limit).toList();
        String next = null;
        if (hasMore && !page.isEmpty()) {
            Whiteboard last = page.getLast();
            next = encode(new Cursor(last.getLesson().getStartAt(), last.getId()));
        }
        List<RelatedBoard> items = page.stream().map(board -> new RelatedBoard(
                board.getPublicId(), board.getLesson().getId(), whiteboardService.displayName(board),
                board.getLesson().getStartAt(), board.getLesson().getDurationMinutes())).toList();
        return new RelatedBoards(whiteboardService.metadata(user, currentPublicId), items, next);
    }

    private String encode(Cursor cursor) {
        String raw = cursor.startAt().toEpochMilli() + ":" + cursor.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decode(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", 2);
            return new Cursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), Long.parseLong(parts[1]));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Некорректный курсор истории досок");
        }
    }

    public record RelatedBoards(WhiteboardService.BoardMetadata current, List<RelatedBoard> items,
                                String nextCursor) {}
    public record RelatedBoard(UUID boardId, Long lessonId, String displayName, Instant lessonStartAt,
                               int durationMinutes) {}
    private record Cursor(Instant startAt, Long id) {}
}

package ru.tutor.codeclass.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.tutor.codeclass.domain.*;
import ru.tutor.codeclass.repository.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

@Service
public class LessonService {
    private final LessonRepository lessons;
    private final UserRepository users;
    private final ConnectionRequestRepository connections;
    private final LessonSeriesRepository seriesRepository;
    private final AttachmentRepository attachments;
    private final WhiteboardService whiteboards;
    private final Path storageRoot;
    private final ZoneId zone;
    private final Clock clock;
    public LessonService(LessonRepository lessons, UserRepository users, ConnectionRequestRepository connections,
                         LessonSeriesRepository seriesRepository, AttachmentRepository attachments, WhiteboardService whiteboards,
                         @org.springframework.beans.factory.annotation.Value("${app.timezone}") String timezone,
                         @org.springframework.beans.factory.annotation.Value("${app.storage-path}") String storagePath,
                         Clock clock) {
        this.lessons = lessons; this.users = users; this.connections = connections; this.seriesRepository = seriesRepository;
        this.attachments = attachments; this.whiteboards = whiteboards;
        this.storageRoot = Paths.get(storagePath).toAbsolutePath().normalize();
        this.zone = ZoneId.of(timezone); this.clock = clock;
    }

    @Transactional
    public Lesson create(User teacher, Long studentId, LocalDateTime localStart, int duration, LessonRecurrence recurrence) {
        requireTeacher(teacher);
        User student = users.findById(studentId).orElseThrow(() -> new IllegalArgumentException("Ученик не найден"));
        if (student.getRole() != Role.STUDENT || !connections.existsByStudentAndTeacherAndStatus(student, teacher, ConnectionStatus.ACCEPTED))
            throw new IllegalArgumentException("Сначала примите ученика");
        Instant start = localStart.atZone(zone).toInstant();
        if (recurrence == LessonRecurrence.WEEKLY) {
            LessonSeries series = seriesRepository.save(new LessonSeries(teacher, student, start, duration));
            return lessons.save(new Lesson(series, 0));
        }
        return lessons.save(new Lesson(teacher, student, start, duration));
    }

    @Transactional
    public Lesson create(User teacher, Long studentId, LocalDateTime localStart, int duration) {
        return create(teacher, studentId, localStart, duration, LessonRecurrence.ONCE);
    }

    @Transactional
    public void reschedule(User teacher, Long id, LocalDateTime localStart, int duration, LessonChangeScope scope) {
        Lesson lesson = requireTeacherLesson(teacher, id);
        if (lesson.getStatus() == LessonStatus.CANCELLED) throw new IllegalArgumentException("Отменённое занятие нельзя перенести");
        Instant newStart = localStart.atZone(zone).toInstant();
        if (scope == LessonChangeScope.FOLLOWING) {
            requireRecurring(lesson);
            Instant seriesOccurrenceStart = lesson.getSeries().occurrenceStart(lesson.getOccurrenceIndex());
            lesson.getSeries().shiftFrom(seriesOccurrenceStart, newStart, duration);
            lessons.findBySeriesIdAndOccurrenceIndexGreaterThanEqualOrderByOccurrenceIndexAsc(
                    lesson.getSeries().getId(), lesson.getOccurrenceIndex()).stream()
                    .filter(item -> item.getStatus() == LessonStatus.SCHEDULED)
                    .forEach(item -> item.reschedule(
                            lesson.getSeries().occurrenceStart(item.getOccurrenceIndex()), duration));
            return;
        }
        lesson.reschedule(newStart, duration);
    }

    @Transactional
    public void reschedule(User teacher, Long id, LocalDateTime localStart, int duration) {
        reschedule(teacher, id, localStart, duration, LessonChangeScope.SINGLE);
    }

    @Transactional
    public void delete(User teacher, Long id, LessonChangeScope scope) {
        Lesson lesson = requireTeacherLesson(teacher, id);
        if (scope == LessonChangeScope.FOLLOWING) {
            requireRecurring(lesson);
            lesson.getSeries().cancelFrom(lesson.getOccurrenceIndex());
            deleteLessons(lessons.findBySeriesIdAndOccurrenceIndexGreaterThanEqualOrderByOccurrenceIndexAsc(
                    lesson.getSeries().getId(), lesson.getOccurrenceIndex()));
            return;
        }
        if (lesson.isRecurring()) lesson.getSeries().exclude(lesson.getOccurrenceIndex());
        deleteLessons(List.of(lesson));
    }

    @Transactional
    public void delete(User teacher, Long id) {
        delete(teacher, id, LessonChangeScope.SINGLE);
    }

    @Transactional
    public DeletedLessons deleteForTeacherStudent(User teacher, User student) {
        requireTeacher(teacher);
        List<Lesson> items = lessons.findByTeacherAndStudentOrderByStartAtAsc(teacher, student);
        List<java.util.UUID> boardIds = whiteboards.publicIdsForLessons(items);
        deleteLessons(items);
        // A lesson references its series, therefore make sure the lesson rows are
        // gone before removing the now-unused series in the same transaction.
        lessons.flush();
        seriesRepository.deleteAll(seriesRepository.findByTeacherAndStudent(teacher, student));
        return new DeletedLessons(items.size(), boardIds);
    }
    @Transactional public void updateMaterials(User teacher, Long id, String homework, String notes) {
        requireTeacherLesson(teacher, id).updateMaterials(blankToNull(homework), blankToNull(notes));
    }

    @Transactional(readOnly = true)
    public Lesson requireAccessible(User user, Long id) {
        Lesson lesson = lessons.findWithStudentById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (user.getRole() == Role.TEACHER && !lesson.getTeacher().getId().equals(user.getId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (user.getRole() == Role.STUDENT && !lesson.getStudent().getId().equals(user.getId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return lesson;
    }

    @Transactional(readOnly = true)
    public Lesson requireTeacherLesson(User teacher, Long id) {
        requireTeacher(teacher);
        Lesson lesson = lessons.findWithStudentById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!lesson.getTeacher().getId().equals(teacher.getId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return lesson;
    }

    @Transactional
    public List<Lesson> forMonth(User user, YearMonth month) {
        Instant from = month.atDay(1).atStartOfDay(zone).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        materializeBetween(user, from, to.minusNanos(1));
        return user.getRole() == Role.TEACHER
                ? lessons.findByTeacherAndStartAtBetweenOrderByStartAtAsc(user, from, to)
                : lessons.findByStudentAndStartAtBetweenOrderByStartAtAsc(user, from, to);
    }

    @Transactional
    public List<Lesson> upcoming(User user) {
        Instant now = clock.instant();
        materializeBetween(user, now, now.plus(180, ChronoUnit.DAYS));
        List<Lesson> result = user.getRole() == Role.TEACHER
                ? lessons.findTop8ByTeacherAndStartAtGreaterThanEqualOrderByStartAtAsc(user, now)
                : lessons.findTop8ByStudentAndStartAtGreaterThanEqualOrderByStartAtAsc(user, now);
        return result.stream().filter(l -> l.getStatus() == LessonStatus.SCHEDULED).toList();
    }

    @Transactional(readOnly = true)
    public List<Lesson> history(User student) {
        Instant now = clock.instant();
        return lessons.findByStudentOrderByStartAtDesc(student).stream()
                .filter(l -> l.getStatus() == LessonStatus.CANCELLED || l.isPast(now)).toList();
    }
    public boolean isPast(Lesson lesson) { return lesson.isPast(clock.instant()); }
    public ZoneId zone() { return zone; }
    private void materializeBetween(User user, Instant from, Instant until) {
        final long weekSeconds = Duration.ofDays(7).toSeconds();
        List<Lesson> generated = new ArrayList<>();
        List<LessonSeries> relevantSeries = user.getRole() == Role.TEACHER
                ? seriesRepository.findByTeacher(user) : seriesRepository.findByStudent(user);
        for (LessonSeries series : relevantSeries) {
            if (series.getAnchorStartAt().isAfter(until)) continue;
            long secondsToFrom = Duration.between(series.getAnchorStartAt(), from).getSeconds();
            int firstIndex = secondsToFrom <= 0 ? 0 : (int) Math.min(
                    Math.ceilDiv(secondsToFrom, weekSeconds), 5_200);
            long secondsToEnd = Duration.between(series.getAnchorStartAt(), until).getSeconds();
            int lastIndex = (int) Math.min(Math.floorDiv(secondsToEnd, weekSeconds), 5_200);
            if (series.getCancelledFromIndex() != null) lastIndex = Math.min(lastIndex, series.getCancelledFromIndex() - 1);
            if (lastIndex < firstIndex) continue;
            var existing = new HashSet<>(lessons.findOccurrenceIndexesBySeriesId(series.getId()));
            for (int index = firstIndex; index <= lastIndex; index++) {
                if (series.includes(index) && !existing.contains(index)) generated.add(new Lesson(series, index));
            }
        }
        if (!generated.isEmpty()) lessons.saveAll(generated);
    }
    private void deleteLessons(List<Lesson> lessonsToDelete) {
        List<String> boardImages = whiteboards.storedImagesForLessons(lessonsToDelete);
        List<Attachment> attachmentsToDelete = new ArrayList<>();
        List<String> attachmentFiles = new ArrayList<>();
        for (Lesson item : lessonsToDelete) {
            List<Attachment> lessonAttachments = attachments.findByLessonOrderByCreatedAtAsc(item);
            attachmentsToDelete.addAll(lessonAttachments);
            for (Attachment attachment : lessonAttachments) {
                attachmentFiles.add(attachment.getStoredName());
            }
        }
        if (!attachmentsToDelete.isEmpty()) {
            attachments.deleteAll(attachmentsToDelete);
            attachments.flush();
        }
        // The database cascades a board's objects and images. Removing board rows
        // before lessons avoids Hibernate retaining a board for a deleted lesson.
        whiteboards.deleteBoardsForLessons(lessonsToDelete);
        lessons.deleteAll(lessonsToDelete);
        lessons.flush();
        deleteAttachmentFilesAfterCommit(attachmentFiles);
        whiteboards.deleteStoredImages(boardImages);
    }

    private void deleteAttachmentFilesAfterCommit(List<String> names) {
        if (names.isEmpty()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                names.forEach(name -> {
                    try {
                        Path file = storageRoot.resolve(name).normalize();
                        if (file.getParent().equals(storageRoot)) Files.deleteIfExists(file);
                    } catch (IOException ignored) { /* inaccessible files are never exposed and can be cleaned later */ }
                });
            }
        });
    }
    private void requireRecurring(Lesson lesson) {
        if (!lesson.isRecurring()) throw new IllegalArgumentException("Это занятие не входит в еженедельную серию");
    }
    private void requireTeacher(User user) { if (user.getRole() != Role.TEACHER) throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    public record DeletedLessons(int lessonCount, List<java.util.UUID> boardIds) {}
}

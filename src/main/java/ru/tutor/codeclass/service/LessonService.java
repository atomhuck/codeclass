package ru.tutor.codeclass.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.tutor.codeclass.domain.*;
import ru.tutor.codeclass.repository.*;
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
    private final ZoneId zone;
    private final Clock clock;
    public LessonService(LessonRepository lessons, UserRepository users, ConnectionRequestRepository connections,
                         LessonSeriesRepository seriesRepository,
                         @org.springframework.beans.factory.annotation.Value("${app.timezone}") String timezone, Clock clock) {
        this.lessons = lessons; this.users = users; this.connections = connections; this.seriesRepository = seriesRepository;
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
            LessonSeries series = seriesRepository.save(new LessonSeries(student, start, duration));
            return lessons.save(new Lesson(series, 0));
        }
        return lessons.save(new Lesson(student, start, duration));
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
    public void cancel(User teacher, Long id, LessonChangeScope scope) {
        Lesson lesson = requireTeacherLesson(teacher, id);
        if (scope == LessonChangeScope.FOLLOWING) {
            requireRecurring(lesson);
            lesson.getSeries().cancelFrom(lesson.getOccurrenceIndex());
            lessons.findBySeriesIdAndOccurrenceIndexGreaterThanEqualOrderByOccurrenceIndexAsc(
                    lesson.getSeries().getId(), lesson.getOccurrenceIndex()).forEach(Lesson::cancel);
            return;
        }
        lesson.cancel();
    }

    @Transactional
    public void cancel(User teacher, Long id) {
        cancel(teacher, id, LessonChangeScope.SINGLE);
    }
    @Transactional public void updateMaterials(User teacher, Long id, String homework, String notes) {
        requireTeacherLesson(teacher, id).updateMaterials(blankToNull(homework), blankToNull(notes));
    }

    @Transactional(readOnly = true)
    public Lesson requireAccessible(User user, Long id) {
        Lesson lesson = lessons.findWithStudentById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (user.getRole() == Role.STUDENT && !lesson.getStudent().getId().equals(user.getId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return lesson;
    }

    @Transactional(readOnly = true)
    public Lesson requireTeacherLesson(User teacher, Long id) {
        requireTeacher(teacher);
        return lessons.findWithStudentById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional
    public List<Lesson> forMonth(User user, YearMonth month) {
        Instant from = month.atDay(1).atStartOfDay(zone).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        materializeBetween(from, to.minusNanos(1));
        return user.getRole() == Role.TEACHER
                ? lessons.findByStartAtBetweenOrderByStartAtAsc(from, to)
                : lessons.findByStudentAndStartAtBetweenOrderByStartAtAsc(user, from, to);
    }

    @Transactional
    public List<Lesson> upcoming(User user) {
        Instant now = clock.instant();
        materializeBetween(now, now.plus(180, ChronoUnit.DAYS));
        List<Lesson> result = user.getRole() == Role.TEACHER
                ? lessons.findTop8ByStartAtGreaterThanEqualOrderByStartAtAsc(now)
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
    private void materializeBetween(Instant from, Instant until) {
        final long weekSeconds = Duration.ofDays(7).toSeconds();
        List<Lesson> generated = new ArrayList<>();
        for (LessonSeries series : seriesRepository.findAll()) {
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
                if (!existing.contains(index)) generated.add(new Lesson(series, index));
            }
        }
        if (!generated.isEmpty()) lessons.saveAll(generated);
    }
    private void requireRecurring(Lesson lesson) {
        if (!lesson.isRecurring()) throw new IllegalArgumentException("Это занятие не входит в еженедельную серию");
    }
    private void requireTeacher(User user) { if (user.getRole() != Role.TEACHER) throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}

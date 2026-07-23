package ru.tutor.codeclass.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.tutor.codeclass.domain.*;
import ru.tutor.codeclass.repository.*;
import java.time.*;
import java.util.List;

@Service
public class LessonService {
    private final LessonRepository lessons;
    private final UserRepository users;
    private final ConnectionRequestRepository connections;
    private final ZoneId zone;
    private final Clock clock;
    public LessonService(LessonRepository lessons, UserRepository users, ConnectionRequestRepository connections,
                         @org.springframework.beans.factory.annotation.Value("${app.timezone}") String timezone, Clock clock) {
        this.lessons = lessons; this.users = users; this.connections = connections; this.zone = ZoneId.of(timezone); this.clock = clock;
    }

    @Transactional
    public Lesson create(User teacher, Long studentId, LocalDateTime localStart, int duration) {
        requireTeacher(teacher);
        User student = users.findById(studentId).orElseThrow(() -> new IllegalArgumentException("Ученик не найден"));
        if (student.getRole() != Role.STUDENT || !connections.existsByStudentAndTeacherAndStatus(student, teacher, ConnectionStatus.ACCEPTED))
            throw new IllegalArgumentException("Сначала примите ученика");
        return lessons.save(new Lesson(student, localStart.atZone(zone).toInstant(), duration));
    }

    @Transactional
    public void reschedule(User teacher, Long id, LocalDateTime localStart, int duration) {
        Lesson lesson = requireTeacherLesson(teacher, id);
        if (lesson.getStatus() == LessonStatus.CANCELLED) throw new IllegalArgumentException("Отменённое занятие нельзя перенести");
        lesson.reschedule(localStart.atZone(zone).toInstant(), duration);
    }

    @Transactional public void cancel(User teacher, Long id) { requireTeacherLesson(teacher, id).cancel(); }
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

    @Transactional(readOnly = true)
    public List<Lesson> forMonth(User user, YearMonth month) {
        Instant from = month.atDay(1).atStartOfDay(zone).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        return user.getRole() == Role.TEACHER
                ? lessons.findByStartAtBetweenOrderByStartAtAsc(from, to)
                : lessons.findByStudentAndStartAtBetweenOrderByStartAtAsc(user, from, to);
    }

    @Transactional(readOnly = true)
    public List<Lesson> upcoming(User user) {
        Instant now = clock.instant();
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
    private void requireTeacher(User user) { if (user.getRole() != Role.TEACHER) throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}

package ru.repethelper.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.repethelper.domain.Lesson;
import ru.repethelper.domain.User;
import java.time.Instant;
import java.util.*;
import java.util.UUID;

public interface LessonRepository extends JpaRepository<Lesson, Long> {
    @EntityGraph(attributePaths = {"student", "teacher"})
    Optional<Lesson> findWithStudentById(Long id);

    @EntityGraph(attributePaths = {"student", "teacher"})
    List<Lesson> findByStartAtBetweenOrderByStartAtAsc(Instant from, Instant to);
    @EntityGraph(attributePaths = {"student", "teacher"})
    List<Lesson> findByTeacherAndStartAtBetweenOrderByStartAtAsc(User teacher, Instant from, Instant to);

    @EntityGraph(attributePaths = {"student", "teacher"})
    List<Lesson> findByStudentAndStartAtBetweenOrderByStartAtAsc(User student, Instant from, Instant to);

    @EntityGraph(attributePaths = {"student", "teacher"})
    List<Lesson> findTop8ByStartAtGreaterThanEqualOrderByStartAtAsc(Instant from);
    @EntityGraph(attributePaths = {"student", "teacher"})
    List<Lesson> findTop8ByTeacherAndStartAtGreaterThanEqualOrderByStartAtAsc(User teacher, Instant from);

    @EntityGraph(attributePaths = {"student", "teacher"})
    List<Lesson> findTop8ByStudentAndStartAtGreaterThanEqualOrderByStartAtAsc(User student, Instant from);

    @EntityGraph(attributePaths = {"student", "teacher"})
    List<Lesson> findByStudentOrderByStartAtDesc(User student);

    @EntityGraph(attributePaths = {"student", "teacher"})
    List<Lesson> findByTeacherAndStudentOrderByStartAtAsc(User teacher, User student);

    @EntityGraph(attributePaths = {"student", "teacher"})
    List<Lesson> findBySeriesIdAndOccurrenceIndexGreaterThanEqualOrderByOccurrenceIndexAsc(UUID seriesId, int occurrenceIndex);

    @Query("select l.occurrenceIndex from Lesson l where l.series.id = :seriesId")
    Set<Integer> findOccurrenceIndexesBySeriesId(UUID seriesId);

    @EntityGraph(attributePaths = {"student", "teacher"})
    List<Lesson> findByStatusAndStartAtAfterAndStartAtLessThanEqualOrderByStartAtAsc(
            ru.repethelper.domain.LessonStatus status, Instant after, Instant until);
}

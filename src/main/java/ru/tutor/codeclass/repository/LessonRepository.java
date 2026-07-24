package ru.tutor.codeclass.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.tutor.codeclass.domain.Lesson;
import ru.tutor.codeclass.domain.User;
import java.time.Instant;
import java.util.*;
import java.util.UUID;

public interface LessonRepository extends JpaRepository<Lesson, Long> {
    @EntityGraph(attributePaths = {"student", "teacher"})
    Optional<Lesson> findWithStudentById(Long id);

    @EntityGraph(attributePaths = "student")
    List<Lesson> findByStartAtBetweenOrderByStartAtAsc(Instant from, Instant to);
    @EntityGraph(attributePaths = "student")
    List<Lesson> findByTeacherAndStartAtBetweenOrderByStartAtAsc(User teacher, Instant from, Instant to);

    @EntityGraph(attributePaths = "student")
    List<Lesson> findByStudentAndStartAtBetweenOrderByStartAtAsc(User student, Instant from, Instant to);

    @EntityGraph(attributePaths = "student")
    List<Lesson> findTop8ByStartAtGreaterThanEqualOrderByStartAtAsc(Instant from);
    @EntityGraph(attributePaths = "student")
    List<Lesson> findTop8ByTeacherAndStartAtGreaterThanEqualOrderByStartAtAsc(User teacher, Instant from);

    @EntityGraph(attributePaths = "student")
    List<Lesson> findTop8ByStudentAndStartAtGreaterThanEqualOrderByStartAtAsc(User student, Instant from);

    @EntityGraph(attributePaths = "student")
    List<Lesson> findByStudentOrderByStartAtDesc(User student);

    @EntityGraph(attributePaths = "student")
    List<Lesson> findBySeriesIdAndOccurrenceIndexGreaterThanEqualOrderByOccurrenceIndexAsc(UUID seriesId, int occurrenceIndex);

    @Query("select l.occurrenceIndex from Lesson l where l.series.id = :seriesId")
    Set<Integer> findOccurrenceIndexesBySeriesId(UUID seriesId);
}

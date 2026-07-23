package ru.tutor.codeclass.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.tutor.codeclass.domain.Lesson;
import ru.tutor.codeclass.domain.User;
import java.time.Instant;
import java.util.*;

public interface LessonRepository extends JpaRepository<Lesson, Long> {
    @EntityGraph(attributePaths = "student")
    Optional<Lesson> findWithStudentById(Long id);

    @EntityGraph(attributePaths = "student")
    List<Lesson> findByStartAtBetweenOrderByStartAtAsc(Instant from, Instant to);

    @EntityGraph(attributePaths = "student")
    List<Lesson> findByStudentAndStartAtBetweenOrderByStartAtAsc(User student, Instant from, Instant to);

    @EntityGraph(attributePaths = "student")
    List<Lesson> findTop8ByStartAtGreaterThanEqualOrderByStartAtAsc(Instant from);

    @EntityGraph(attributePaths = "student")
    List<Lesson> findTop8ByStudentAndStartAtGreaterThanEqualOrderByStartAtAsc(User student, Instant from);

    @EntityGraph(attributePaths = "student")
    List<Lesson> findByStudentOrderByStartAtDesc(User student);
}

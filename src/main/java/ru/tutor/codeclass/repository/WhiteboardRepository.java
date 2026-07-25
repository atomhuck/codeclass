package ru.tutor.codeclass.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import ru.tutor.codeclass.domain.*;
import java.util.*;

public interface WhiteboardRepository extends JpaRepository<Whiteboard, Long> {
    Optional<Whiteboard> findByLesson(Lesson lesson);

    @EntityGraph(attributePaths = {"lesson", "lesson.student", "lesson.teacher"})
    Optional<Whiteboard> findByPublicId(UUID publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"lesson", "lesson.student", "lesson.teacher"})
    @Query("select b from Whiteboard b where b.publicId = :publicId")
    Optional<Whiteboard> findLockedByPublicId(@Param("publicId") UUID publicId);

    @EntityGraph(attributePaths = {"lesson", "lesson.student", "lesson.teacher"})
    List<Whiteboard> findByLessonIn(Collection<Lesson> lessons);
    long countByLessonIn(Collection<Lesson> lessons);
}

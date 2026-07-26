package ru.repethelper.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.repethelper.domain.*;
import java.util.*;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {
    List<Attachment> findByLessonOrderByCreatedAtAsc(Lesson lesson);
    long countByLessonAndCategory(Lesson lesson, AttachmentCategory category);
    long countByLessonIn(Collection<Lesson> lessons);
    @EntityGraph(attributePaths = {"lesson", "lesson.student"})
    Optional<Attachment> findWithLessonById(Long id);
}

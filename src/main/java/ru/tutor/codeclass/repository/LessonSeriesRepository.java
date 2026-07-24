package ru.tutor.codeclass.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.tutor.codeclass.domain.LessonSeries;
import ru.tutor.codeclass.domain.User;
import java.util.*;

public interface LessonSeriesRepository extends JpaRepository<LessonSeries, UUID> {
    @EntityGraph(attributePaths = "student")
    List<LessonSeries> findAll();
    @EntityGraph(attributePaths = "student")
    List<LessonSeries> findByTeacher(User teacher);
    @EntityGraph(attributePaths = "student")
    List<LessonSeries> findByStudent(User student);
}

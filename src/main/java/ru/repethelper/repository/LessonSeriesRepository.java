package ru.repethelper.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.repethelper.domain.LessonSeries;
import ru.repethelper.domain.User;
import java.util.*;

public interface LessonSeriesRepository extends JpaRepository<LessonSeries, UUID> {
    @EntityGraph(attributePaths = {"student", "teacher"})
    List<LessonSeries> findAll();
    @EntityGraph(attributePaths = {"student", "teacher"})
    List<LessonSeries> findByTeacher(User teacher);
    @EntityGraph(attributePaths = {"student", "teacher"})
    List<LessonSeries> findByStudent(User student);
    List<LessonSeries> findByTeacherAndStudent(User teacher, User student);
}

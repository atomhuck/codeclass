package ru.repethelper.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import ru.repethelper.domain.LessonSeries;
import ru.repethelper.domain.User;
import java.util.*;

public interface LessonSeriesRepository extends JpaRepository<LessonSeries, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from LessonSeries s where s.id = :id")
    Optional<LessonSeries> findLockedById(UUID id);

    @EntityGraph(attributePaths = {"student", "teacher"})
    List<LessonSeries> findAll();
    @EntityGraph(attributePaths = {"student", "teacher"})
    List<LessonSeries> findByTeacher(User teacher);
    @EntityGraph(attributePaths = {"student", "teacher"})
    List<LessonSeries> findByStudent(User student);
    List<LessonSeries> findByTeacherAndStudent(User teacher, User student);
}

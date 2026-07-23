package ru.tutor.codeclass.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.tutor.codeclass.domain.LessonSeries;
import java.util.*;

public interface LessonSeriesRepository extends JpaRepository<LessonSeries, UUID> {
    @EntityGraph(attributePaths = "student")
    List<LessonSeries> findAll();
}

package ru.repethelper.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.repethelper.domain.LessonPaymentRecord;

import java.util.Optional;

public interface LessonPaymentRecordRepository extends JpaRepository<LessonPaymentRecord, Long> {
    Optional<LessonPaymentRecord> findByLessonId(Long lessonId);
}

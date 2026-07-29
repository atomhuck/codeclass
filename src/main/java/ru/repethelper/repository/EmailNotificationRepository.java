package ru.repethelper.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.repethelper.domain.EmailNotification;
import ru.repethelper.domain.EmailNotificationStatus;

import java.time.Instant;
import java.util.List;

public interface EmailNotificationRepository extends JpaRepository<EmailNotification, Long> {
    @Modifying
    @Query(value = """
            insert into email_notifications(
                type, recipient_email, student_id, teacher_id, lesson_id, series_id,
                payload, dedupe_key, status, available_at, attempt_count, created_at
            ) values (
                :type, :recipient, :studentId, :teacherId, :lessonId, cast(:seriesId as uuid),
                cast(:payload as jsonb), :dedupeKey, 'PENDING', :availableAt, 0, :createdAt
            )
            on conflict (dedupe_key) do nothing
            """, nativeQuery = true)
    int enqueue(@Param("type") String type, @Param("recipient") String recipient,
                @Param("studentId") Long studentId, @Param("teacherId") Long teacherId,
                @Param("lessonId") Long lessonId, @Param("seriesId") String seriesId,
                @Param("payload") String payload, @Param("dedupeKey") String dedupeKey,
                @Param("availableAt") Instant availableAt, @Param("createdAt") Instant createdAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<EmailNotification> findTop25ByStatusAndAvailableAtLessThanEqualOrderByIdAsc(
            EmailNotificationStatus status, Instant availableAt);

    @Modifying
    @Query(value = """
            update email_notifications
               set status = 'PENDING', processing_started_at = null, available_at = :now
             where status = 'PROCESSING' and processing_started_at < :staleBefore
            """, nativeQuery = true)
    int releaseStale(@Param("staleBefore") Instant staleBefore, @Param("now") Instant now);

    @Modifying
    @Query(value = """
            update email_notifications set status = 'CANCELLED'
             where lesson_id = :lessonId and type = 'LESSON_REMINDER' and status = 'PENDING'
            """, nativeQuery = true)
    int cancelPendingReminder(@Param("lessonId") Long lessonId);

    @Modifying
    @Query(value = """
            update email_notifications set status = 'CANCELLED'
             where lesson_id = :lessonId
               and type in ('LESSON_REMINDER', 'HOMEWORK_UPDATED')
               and status = 'PENDING'
            """, nativeQuery = true)
    int cancelTransientForDeletedLesson(@Param("lessonId") Long lessonId);
}

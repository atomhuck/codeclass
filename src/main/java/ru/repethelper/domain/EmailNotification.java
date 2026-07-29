package ru.repethelper.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_notifications")
public class EmailNotification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private EmailNotificationType type;
    @Column(name = "recipient_email", nullable = false, length = 254)
    private String recipientEmail;
    @Column(name = "student_id")
    private Long studentId;
    @Column(name = "teacher_id")
    private Long teacherId;
    @Column(name = "lesson_id")
    private Long lessonId;
    @Column(name = "series_id")
    private UUID seriesId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;
    @Column(name = "dedupe_key", nullable = false, length = 180)
    private String dedupeKey;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EmailNotificationStatus status;
    @Column(name = "available_at", nullable = false)
    private Instant availableAt;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "processing_started_at")
    private Instant processingStartedAt;
    @Column(name = "sent_at")
    private Instant sentAt;
    @Column(name = "last_error", length = 500)
    private String lastError;

    protected EmailNotification() {}

    public Long getId() { return id; }
    public EmailNotificationType getType() { return type; }
    public String getRecipientEmail() { return recipientEmail; }
    public String getPayload() { return payload; }
    public EmailNotificationStatus getStatus() { return status; }
    public Instant getAvailableAt() { return availableAt; }
    public int getAttemptCount() { return attemptCount; }

    public void claim(Instant now) {
        status = EmailNotificationStatus.PROCESSING;
        processingStartedAt = now;
    }

    public void sent(Instant now) {
        status = EmailNotificationStatus.SENT;
        sentAt = now;
        processingStartedAt = null;
        lastError = null;
    }

    public void retryOrFail(Instant now, Instant retryAt, String error, int maxAttempts) {
        attemptCount++;
        processingStartedAt = null;
        lastError = error == null ? "Неизвестная ошибка SMTP"
                : error.substring(0, Math.min(error.length(), 500));
        if (attemptCount >= maxAttempts) {
            status = EmailNotificationStatus.FAILED;
        } else {
            status = EmailNotificationStatus.PENDING;
            availableAt = retryAt;
        }
    }
}

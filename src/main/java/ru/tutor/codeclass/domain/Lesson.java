package ru.tutor.codeclass.domain;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;

@Entity
@Table(name = "lessons")
public class Lesson {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "student_id")
    private User student;
    @Column(name = "start_at", nullable = false)
    private Instant startAt;
    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private LessonStatus status;
    @Column(name = "homework_text", columnDefinition = "text")
    private String homeworkText;
    @Column(name = "lesson_notes_text", columnDefinition = "text")
    private String lessonNotesText;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    protected Lesson() {}
    public Lesson(User student, Instant startAt, int durationMinutes) {
        this.student = student; this.startAt = startAt; this.durationMinutes = durationMinutes;
        this.status = LessonStatus.SCHEDULED; this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public Long getId() { return id; }
    public User getStudent() { return student; }
    public Instant getStartAt() { return startAt; }
    public int getDurationMinutes() { return durationMinutes; }
    public LessonStatus getStatus() { return status; }
    public String getHomeworkText() { return homeworkText; }
    public String getLessonNotesText() { return lessonNotesText; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getEndAt() { return startAt.plus(Duration.ofMinutes(durationMinutes)); }
    public boolean isPast(Instant now) { return status != LessonStatus.CANCELLED && !getEndAt().isAfter(now); }
    public void reschedule(Instant startAt, int durationMinutes) { this.startAt = startAt; this.durationMinutes = durationMinutes; touch(); }
    public void updateMaterials(String homeworkText, String notesText) { this.homeworkText = homeworkText; this.lessonNotesText = notesText; touch(); }
    public void cancel() { this.status = LessonStatus.CANCELLED; touch(); }
    private void touch() { this.updatedAt = Instant.now(); }
}

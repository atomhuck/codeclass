package ru.tutor.codeclass.domain;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "lesson_series")
public class LessonSeries {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private User student;

    @Column(name = "anchor_start_at", nullable = false)
    private Instant anchorStartAt;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(name = "cancelled_from_index")
    private Integer cancelledFromIndex;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LessonSeries() {}

    public LessonSeries(User student, Instant anchorStartAt, int durationMinutes) {
        this.student = student;
        this.anchorStartAt = anchorStartAt;
        this.durationMinutes = durationMinutes;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public UUID getId() { return id; }
    public User getStudent() { return student; }
    public Instant getAnchorStartAt() { return anchorStartAt; }
    public int getDurationMinutes() { return durationMinutes; }
    public Integer getCancelledFromIndex() { return cancelledFromIndex; }
    public Instant occurrenceStart(int index) { return anchorStartAt.plus(Duration.ofDays(7L * index)); }
    public boolean includes(int index) { return cancelledFromIndex == null || index < cancelledFromIndex; }

    public void shiftFrom(Instant oldOccurrenceStart, Instant newOccurrenceStart, int newDurationMinutes) {
        anchorStartAt = anchorStartAt.plus(Duration.between(oldOccurrenceStart, newOccurrenceStart));
        durationMinutes = newDurationMinutes;
        updatedAt = Instant.now();
    }

    public void cancelFrom(int index) {
        if (cancelledFromIndex == null || index < cancelledFromIndex) cancelledFromIndex = index;
        updatedAt = Instant.now();
    }
}

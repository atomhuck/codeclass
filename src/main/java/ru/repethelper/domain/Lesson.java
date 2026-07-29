package ru.repethelper.domain;

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
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "teacher_id")
    private User teacher;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "series_id")
    private LessonSeries series;
    @Column(name = "occurrence_index")
    private Integer occurrenceIndex;
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
    @Column(name = "teacher_private_note", columnDefinition = "text")
    private String teacherPrivateNote;
    @Column(name = "meeting_url", columnDefinition = "text")
    private String meetingUrl;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    protected Lesson() {}
    public Lesson(User teacher, User student, Instant startAt, int durationMinutes) {
        this.teacher = teacher; this.student = student; this.startAt = startAt; this.durationMinutes = durationMinutes;
        this.status = LessonStatus.SCHEDULED; this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public Lesson(LessonSeries series, int occurrenceIndex) {
        this(series.getTeacher(), series.getStudent(), series.occurrenceStart(occurrenceIndex), series.getDurationMinutes());
        this.series = series;
        this.occurrenceIndex = occurrenceIndex;
    }
    public Long getId() { return id; }
    public User getStudent() { return student; }
    public User getTeacher() { return teacher; }
    public LessonSeries getSeries() { return series; }
    public Integer getOccurrenceIndex() { return occurrenceIndex; }
    public boolean isRecurring() { return series != null; }
    public Instant getStartAt() { return startAt; }
    public int getDurationMinutes() { return durationMinutes; }
    public LessonStatus getStatus() { return status; }
    public String getHomeworkText() { return homeworkText; }
    public String getLessonNotesText() { return lessonNotesText; }
    public String getTeacherPrivateNote() { return teacherPrivateNote; }
    public String getMeetingUrl() { return meetingUrl; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getEndAt() { return startAt.plus(Duration.ofMinutes(durationMinutes)); }
    public boolean isPast(Instant now) { return status != LessonStatus.CANCELLED && !getEndAt().isAfter(now); }
    public void reschedule(Instant startAt, int durationMinutes) { this.startAt = startAt; this.durationMinutes = durationMinutes; touch(); }
    public void updateMaterials(String homeworkText, String notesText) { this.homeworkText = homeworkText; this.lessonNotesText = notesText; touch(); }
    public void updateTeacherPrivateNote(String teacherPrivateNote) { this.teacherPrivateNote = teacherPrivateNote; touch(); }
    public void updateMeetingUrl(String meetingUrl) { this.meetingUrl = meetingUrl; touch(); }
    public void cancel() { this.status = LessonStatus.CANCELLED; touch(); }
    private void touch() { this.updatedAt = Instant.now(); }
}

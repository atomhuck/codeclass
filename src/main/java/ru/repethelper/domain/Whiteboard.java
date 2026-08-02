package ru.repethelper.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "whiteboards")
public class Whiteboard {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "lesson_id", unique = true)
    private Lesson lesson;
    @Column(name = "public_id", nullable = false, unique = true)
    private UUID publicId;
    @Column(nullable = false)
    private long revision;
    @Column(name = "custom_name", length = 120)
    private String customName;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Whiteboard() {}

    public Whiteboard(Lesson lesson) {
        this.lesson = lesson;
        this.publicId = UUID.randomUUID();
        this.revision = 0;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public Long getId() { return id; }
    public Lesson getLesson() { return lesson; }
    public UUID getPublicId() { return publicId; }
    public long getRevision() { return revision; }
    public String getCustomName() { return customName; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void rename(String value) {
        customName = value == null || value.isBlank() ? null : value.strip();
        updatedAt = Instant.now();
    }
    public long nextRevision() {
        revision++;
        updatedAt = Instant.now();
        return revision;
    }
}

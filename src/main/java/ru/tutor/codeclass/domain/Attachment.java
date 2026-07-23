package ru.tutor.codeclass.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "attachments")
public class Attachment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "lesson_id")
    private Lesson lesson;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private AttachmentCategory category;
    @Column(name = "original_name", nullable = false)
    private String originalName;
    @Column(name = "stored_name", nullable = false, unique = true, length = 80)
    private String storedName;
    @Column(name = "content_type", nullable = false, length = 150)
    private String contentType;
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    protected Attachment() {}
    public Attachment(Lesson lesson, AttachmentCategory category, String originalName, String storedName, String contentType, long sizeBytes) {
        this.lesson = lesson; this.category = category; this.originalName = originalName; this.storedName = storedName;
        this.contentType = contentType; this.sizeBytes = sizeBytes; this.createdAt = Instant.now();
    }
    public Long getId() { return id; }
    public Lesson getLesson() { return lesson; }
    public AttachmentCategory getCategory() { return category; }
    public String getOriginalName() { return originalName; }
    public String getStoredName() { return storedName; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public Instant getCreatedAt() { return createdAt; }
}

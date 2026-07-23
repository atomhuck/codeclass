package ru.tutor.codeclass.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "whiteboard_images")
public class WhiteboardImage {
    @Id
    @Column(name = "object_id")
    private UUID objectId;
    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "object_id")
    private WhiteboardObject object;
    @Column(name = "original_name", nullable = false)
    private String originalName;
    @Column(name = "stored_name", nullable = false, unique = true, length = 80)
    private String storedName;
    @Column(name = "content_type", nullable = false, length = 80)
    private String contentType;
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;
    @Column(nullable = false)
    private int width;
    @Column(nullable = false)
    private int height;

    protected WhiteboardImage() {}

    public WhiteboardImage(WhiteboardObject object, String originalName, String storedName,
                           String contentType, long sizeBytes, int width, int height) {
        this.object = object;
        this.originalName = originalName;
        this.storedName = storedName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.width = width;
        this.height = height;
    }

    public UUID getObjectId() { return objectId; }
    public WhiteboardObject getObject() { return object; }
    public String getOriginalName() { return originalName; }
    public String getStoredName() { return storedName; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
}

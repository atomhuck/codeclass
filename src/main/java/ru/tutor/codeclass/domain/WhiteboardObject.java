package ru.tutor.codeclass.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "whiteboard_objects")
public class WhiteboardObject {
    @Id
    private UUID id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "board_id")
    private Whiteboard board;
    @Enumerated(EnumType.STRING)
    @Column(name = "object_type", nullable = false, length = 20)
    private WhiteboardObjectType type;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "object_data", nullable = false, columnDefinition = "jsonb")
    private String data;
    @Column(name = "z_order", nullable = false)
    private long zOrder;
    @Column(name = "object_version", nullable = false)
    private long version;
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WhiteboardObject() {}

    public WhiteboardObject(UUID id, Whiteboard board, WhiteboardObjectType type, String data,
                            long zOrder, User createdBy) {
        this.id = id;
        this.board = board;
        this.type = type;
        this.data = data;
        this.zOrder = zOrder;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public UUID getId() { return id; }
    public Whiteboard getBoard() { return board; }
    public WhiteboardObjectType getType() { return type; }
    public String getData() { return data; }
    public long getZOrder() { return zOrder; }
    public long getVersion() { return version; }
    public User getCreatedBy() { return createdBy; }
    public void update(String data) {
        this.data = data;
        version++;
        updatedAt = Instant.now();
    }
}

package ru.repethelper.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "connection_requests")
public class ConnectionRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "student_id")
    private User student;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "teacher_id")
    private User teacher;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private ConnectionStatus status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "processed_at")
    private Instant processedAt;
    protected ConnectionRequest() {}
    public ConnectionRequest(User student, User teacher) {
        this.student = student; this.teacher = teacher; this.status = ConnectionStatus.PENDING; this.createdAt = Instant.now();
    }
    public Long getId() { return id; }
    public User getStudent() { return student; }
    public User getTeacher() { return teacher; }
    public ConnectionStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getProcessedAt() { return processedAt; }
    public void accept() { status = ConnectionStatus.ACCEPTED; processedAt = Instant.now(); }
    public void reject() { status = ConnectionStatus.REJECTED; processedAt = Instant.now(); }
}

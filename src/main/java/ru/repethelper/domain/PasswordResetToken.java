package ru.repethelper.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "user_id")
    private User user;
    @Column(name = "token_hash", nullable = false, unique = true, length = 100)
    private String tokenHash;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "used_at")
    private Instant usedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;
    protected PasswordResetToken() {}
    public PasswordResetToken(User user, String tokenHash, Instant expiresAt) {
        this.user = user; this.tokenHash = tokenHash; this.expiresAt = expiresAt; this.createdAt = Instant.now();
    }
    public User getUser() { return user; }
    public String getTokenHash() { return tokenHash; }
    public boolean isUsable(Instant now) {
        return usedAt == null && failedAttempts < 5 && expiresAt.isAfter(now);
    }
    public void recordFailure() { failedAttempts++; }
    public void use() { usedAt = Instant.now(); }
}

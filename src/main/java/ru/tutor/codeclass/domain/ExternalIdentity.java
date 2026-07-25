package ru.tutor.codeclass.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "external_identities", uniqueConstraints = {
        @UniqueConstraint(name = "uq_external_identity_provider_subject", columnNames = {"provider", "provider_subject"}),
        @UniqueConstraint(name = "uq_external_identity_user_provider", columnNames = {"user_id", "provider"})
})
public class ExternalIdentity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(nullable = false, length = 20)
    private String provider;
    @Column(name = "provider_subject", nullable = false, length = 120)
    private String providerSubject;
    @Column(name = "email_at_link", length = 254)
    private String emailAtLink;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected ExternalIdentity() { }
    public ExternalIdentity(User user, String provider, String providerSubject, String emailAtLink) {
        this.user = user; this.provider = provider; this.providerSubject = providerSubject;
        this.emailAtLink = emailAtLink; this.createdAt = Instant.now(); this.lastLoginAt = this.createdAt;
    }
    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getProvider() { return provider; }
    public String getProviderSubject() { return providerSubject; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void recordLogin() { this.lastLoginAt = Instant.now(); }
}

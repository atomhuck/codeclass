package ru.tutor.codeclass.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "app_users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 40)
    private String username;
    @Column(name = "password_hash", length = 100)
    private String passwordHash;
    @Column(length = 254)
    private String email;
    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;
    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private Role role;
    @Column(nullable = false)
    private boolean enabled = true;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "auth_version", nullable = false)
    private long authVersion;
    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;
    @Column(name = "failed_login_window_started_at")
    private Instant failedLoginWindowStartedAt;
    @Column(name = "locked_until")
    private Instant lockedUntil;
    @Column(name = "terms_version", length = 30)
    private String termsVersion;
    @Column(name = "terms_accepted_at")
    private Instant termsAcceptedAt;
    @Column(name = "privacy_version", length = 30)
    private String privacyVersion;
    @Column(name = "personal_data_consent_at")
    private Instant personalDataConsentAt;

    protected User() {}
    public User(String username, String passwordHash, String displayName, Role role) {
        this.username = username; this.passwordHash = passwordHash; this.displayName = displayName;
        this.role = role; this.enabled = true; this.createdAt = Instant.now();
    }
    public User(String username, String email, String passwordHash, String displayName, Role role) {
        this(username, passwordHash, displayName, role);
        this.email = email;
    }
    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getEmail() { return email; }
    public Instant getEmailVerifiedAt() { return emailVerifiedAt; }
    public String getDisplayName() { return displayName; }
    public Role getRole() { return role; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public long getAuthVersion() { return authVersion; }
    public Instant getLockedUntil() { return lockedUntil; }
    public String getTermsVersion() { return termsVersion; }
    public Instant getTermsAcceptedAt() { return termsAcceptedAt; }
    public String getPrivacyVersion() { return privacyVersion; }
    public Instant getPersonalDataConsentAt() { return personalDataConsentAt; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public void setEmail(String email) {
        if (this.email == null || !this.email.equalsIgnoreCase(email)) this.emailVerifiedAt = null;
        this.email = email;
    }
    public void verifyEmail() { this.emailVerifiedAt = Instant.now(); }
    public void acceptLegal(String termsVersion, String privacyVersion) {
        this.termsVersion = termsVersion;
        this.termsAcceptedAt = Instant.now();
        this.privacyVersion = privacyVersion;
        this.personalDataConsentAt = this.termsAcceptedAt;
    }
    public boolean hasAccepted(String termsVersion, String privacyVersion) {
        return termsVersion.equals(this.termsVersion) && termsAcceptedAt != null
                && privacyVersion.equals(this.privacyVersion) && personalDataConsentAt != null;
    }
    public boolean isEmailVerified() { return email != null && emailVerifiedAt != null; }
    public boolean isLocked(Instant now) { return lockedUntil != null && lockedUntil.isAfter(now); }
    public void recordLoginFailure(Instant now) {
        if (failedLoginWindowStartedAt == null || failedLoginWindowStartedAt.plusSeconds(900).isBefore(now)) {
            failedLoginWindowStartedAt = now;
            failedLoginAttempts = 1;
        } else {
            failedLoginAttempts++;
        }
        if (failedLoginAttempts >= 5) lockedUntil = now.plusSeconds(900);
    }
    public void clearLoginFailures() {
        failedLoginAttempts = 0;
        failedLoginWindowStartedAt = null;
        lockedUntil = null;
    }
    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.authVersion++;
        clearLoginFailures();
    }
    public void invalidateSessions() { this.authVersion++; }
    public boolean hasPassword() { return passwordHash != null && !passwordHash.isBlank(); }
}

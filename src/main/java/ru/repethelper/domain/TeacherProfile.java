package ru.repethelper.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "teacher_profiles")
public class TeacherProfile {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @OneToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "user_id", unique = true)
    private User user;
    @Column(name = "invite_code", nullable = false, unique = true, length = 30, updatable = false)
    private String inviteCode;
    protected TeacherProfile() {}
    public TeacherProfile(User user, String inviteCode) { this.user = user; this.inviteCode = inviteCode; }
    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getInviteCode() { return inviteCode; }
}

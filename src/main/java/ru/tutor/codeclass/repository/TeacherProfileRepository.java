package ru.tutor.codeclass.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.tutor.codeclass.domain.TeacherProfile;
import java.util.Optional;

public interface TeacherProfileRepository extends JpaRepository<TeacherProfile, Long> {
    Optional<TeacherProfile> findByInviteCodeIgnoreCase(String inviteCode);
    Optional<TeacherProfile> findByUserId(Long userId);
    boolean existsByInviteCodeIgnoreCaseAndIdNot(String inviteCode, Long id);
}

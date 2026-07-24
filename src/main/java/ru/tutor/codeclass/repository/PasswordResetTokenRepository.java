package ru.tutor.codeclass.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.tutor.codeclass.domain.PasswordResetToken;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);
    void deleteByUserId(Long userId);
}

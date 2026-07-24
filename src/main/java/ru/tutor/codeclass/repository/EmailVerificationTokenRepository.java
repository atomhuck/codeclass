package ru.tutor.codeclass.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.tutor.codeclass.domain.EmailVerificationToken;
import java.util.Optional;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {
    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);
    void deleteByUserId(Long userId);
}

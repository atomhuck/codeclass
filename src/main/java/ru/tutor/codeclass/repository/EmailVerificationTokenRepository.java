package ru.tutor.codeclass.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.tutor.codeclass.domain.EmailVerificationToken;
import java.util.Optional;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerificationToken> findFirstByUserIdOrderByCreatedAtDesc(Long userId);
    void deleteByUserId(Long userId);
}

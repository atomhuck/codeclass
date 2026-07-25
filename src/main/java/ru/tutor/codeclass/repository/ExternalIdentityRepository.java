package ru.tutor.codeclass.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.tutor.codeclass.domain.ExternalIdentity;
import java.util.Optional;

public interface ExternalIdentityRepository extends JpaRepository<ExternalIdentity, Long> {
    Optional<ExternalIdentity> findByProviderAndProviderSubject(String provider, String providerSubject);
    Optional<ExternalIdentity> findByUserIdAndProvider(Long userId, String provider);
    boolean existsByUserIdAndProvider(Long userId, String provider);
}

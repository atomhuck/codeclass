package ru.tutor.codeclass.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.tutor.codeclass.domain.User;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsernameIgnoreCase(String username);
    boolean existsByUsernameIgnoreCase(String username);
}

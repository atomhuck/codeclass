package ru.tutor.codeclass.service;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.tutor.codeclass.domain.*;
import ru.tutor.codeclass.repository.UserRepository;

@Service
public class AccountService implements UserDetailsService {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    public AccountService(UserRepository users, PasswordEncoder encoder) { this.users = users; this.encoder = encoder; }

    @Override @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = users.findByUsernameIgnoreCase(username).orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден"));
        return org.springframework.security.core.userdetails.User.withUsername(user.getUsername())
                .password(user.getPasswordHash()).roles(user.getRole().name()).disabled(!user.isEnabled()).build();
    }

    @Transactional
    public User registerStudent(String displayName, String username, String password) {
        String normalized = username.trim().toLowerCase();
        if (users.existsByUsernameIgnoreCase(normalized)) throw new IllegalArgumentException("Этот логин уже занят");
        return users.save(new User(normalized, encoder.encode(password), displayName.trim(), Role.STUDENT));
    }

    @Transactional(readOnly = true)
    public User requireByUsername(String username) {
        return users.findByUsernameIgnoreCase(username).orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден"));
    }
}

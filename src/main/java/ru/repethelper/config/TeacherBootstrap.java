package ru.repethelper.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.repethelper.domain.*;
import ru.repethelper.repository.*;

@Component
public class TeacherBootstrap implements ApplicationRunner {
    private final UserRepository users;
    private final TeacherProfileRepository profiles;
    private final PasswordEncoder encoder;
    @Value("${app.teacher.username}") private String username;
    @Value("${app.teacher.password}") private String password;
    @Value("${app.teacher.name}") private String name;
    @Value("${app.teacher.code}") private String code;
    public TeacherBootstrap(UserRepository users, TeacherProfileRepository profiles, PasswordEncoder encoder) {
        this.users = users; this.profiles = profiles; this.encoder = encoder;
    }
    @Override @Transactional
    public void run(ApplicationArguments args) {
        User teacher = users.findByUsernameIgnoreCase(username).orElseGet(() ->
                users.save(new User(username.trim().toLowerCase(), encoder.encode(password), name.trim(), Role.TEACHER)));
        profiles.findByUserId(teacher.getId()).orElseGet(() -> profiles.save(new TeacherProfile(teacher, code.trim())));
    }
}

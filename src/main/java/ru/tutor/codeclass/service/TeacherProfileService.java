package ru.tutor.codeclass.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.tutor.codeclass.domain.*;
import ru.tutor.codeclass.repository.TeacherProfileRepository;
import java.security.SecureRandom;

@Service
public class TeacherProfileService {
    private final TeacherProfileRepository profiles;
    private final SecureRandom random = new SecureRandom();
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    public TeacherProfileService(TeacherProfileRepository profiles) { this.profiles = profiles; }
    @Transactional(readOnly = true)
    public TeacherProfile requireFor(User teacher) {
        return profiles.findByUserId(teacher.getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    @Transactional
    public void update(User teacher, String displayName, String inviteCode) {
        TeacherProfile profile = requireFor(teacher);
        String normalizedCode = inviteCode.trim();
        if (profiles.existsByInviteCodeIgnoreCaseAndIdNot(normalizedCode, profile.getId()))
            throw new IllegalArgumentException("Этот код уже используется");
        profile.getUser().setDisplayName(displayName.trim());
        profile.setInviteCode(normalizedCode);
    }

    @Transactional
    public String regenerateCode(User teacher) {
        TeacherProfile profile = requireFor(teacher);
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder value = new StringBuilder("T-");
            for (int i = 0; i < 8; i++) value.append(ALPHABET[random.nextInt(ALPHABET.length)]);
            if (profiles.findByInviteCodeIgnoreCase(value.toString()).isEmpty()) {
                profile.setInviteCode(value.toString());
                return value.toString();
            }
        }
        throw new IllegalStateException("Не удалось создать новый код");
    }
}

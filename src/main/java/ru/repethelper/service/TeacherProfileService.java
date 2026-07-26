package ru.repethelper.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.repethelper.domain.*;
import ru.repethelper.repository.TeacherProfileRepository;

@Service
public class TeacherProfileService {
    private final TeacherProfileRepository profiles;
    public TeacherProfileService(TeacherProfileRepository profiles) { this.profiles = profiles; }
    @Transactional(readOnly = true)
    public TeacherProfile requireFor(User teacher) {
        return profiles.findByUserId(teacher.getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    @Transactional
    public void update(User teacher, String displayName) {
        TeacherProfile profile = requireFor(teacher);
        profile.getUser().setDisplayName(displayName.trim());
    }
}

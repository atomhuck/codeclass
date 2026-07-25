package ru.tutor.codeclass.service;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.tutor.codeclass.domain.TeacherProfile;
import ru.tutor.codeclass.repository.TeacherProfileRepository;

import java.util.Optional;

@Service
public class InvitationService {
    public static final String PENDING_INVITE_CODE = InvitationService.class.getName() + ".pendingInviteCode";
    private final TeacherProfileRepository profiles;

    public InvitationService(TeacherProfileRepository profiles) { this.profiles = profiles; }

    @Transactional(readOnly = true)
    public InviteTarget requireActive(String code) {
        String normalized = code == null ? "" : code.trim();
        TeacherProfile profile = profiles.findWithUserByInviteCodeIgnoreCase(normalized)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!profile.getUser().isEnabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return new InviteTarget(profile.getInviteCode(), profile.getUser().getId(), profile.getUser().getDisplayName());
    }

    public void remember(HttpSession session, InviteTarget target) {
        session.setAttribute(PENDING_INVITE_CODE, target.code());
    }

    public Optional<String> pendingPath(HttpSession session) {
        if (session == null) return Optional.empty();
        Object code = session.getAttribute(PENDING_INVITE_CODE);
        if (!(code instanceof String value) || value.isBlank()) return Optional.empty();
        return Optional.of("/invite/" + value);
    }

    public void clear(HttpSession session) {
        if (session != null) session.removeAttribute(PENDING_INVITE_CODE);
    }

    public record InviteTarget(String code, Long teacherId, String teacherName) {}
}

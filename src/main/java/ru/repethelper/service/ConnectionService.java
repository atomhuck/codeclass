package ru.repethelper.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.repethelper.domain.*;
import ru.repethelper.repository.*;
import java.util.List;

@Service
public class ConnectionService {
    private final ConnectionRequestRepository requests;
    private final TeacherProfileRepository profiles;
    private final AppNotificationService notifications;
    public ConnectionService(ConnectionRequestRepository requests, TeacherProfileRepository profiles,
                             AppNotificationService notifications) {
        this.requests = requests; this.profiles = profiles; this.notifications = notifications;
    }

    @Transactional
    public void send(User student, String code) {
        TeacherProfile profile = profiles.findByInviteCodeIgnoreCase(code.trim())
                .orElseThrow(() -> new IllegalArgumentException("Преподаватель с таким кодом не найден"));
        send(student, profile.getUser().getId());
    }

    @Transactional
    public void send(User student, Long teacherId) {
        if (student.getRole() != Role.STUDENT) throw new IllegalArgumentException("Запрос может отправить только ученик");
        User teacher = profiles.findByUserId(teacherId).map(TeacherProfile::getUser)
                .orElseThrow(() -> new IllegalArgumentException("Преподаватель не найден"));
        if (requests.existsByStudentAndTeacherAndStatus(student, teacher, ConnectionStatus.ACCEPTED))
            throw new IllegalArgumentException("Вы уже прикреплены к этому преподавателю");
        if (requests.existsByStudentAndTeacherAndStatus(student, teacher, ConnectionStatus.PENDING))
            throw new IllegalArgumentException("Запрос уже ожидает решения");
        ConnectionRequest request = requests.save(new ConnectionRequest(student, teacher));
        notifications.connectionRequested(request);
    }

    @Transactional
    public void process(User teacher, Long requestId, boolean accept) {
        ConnectionRequest request = requests.findWithRelationsById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!request.getTeacher().getId().equals(teacher.getId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (request.getStatus() != ConnectionStatus.PENDING) throw new IllegalArgumentException("Запрос уже обработан");
        if (accept) request.accept(); else request.reject();
        notifications.connectionProcessed(request);
    }

    @Transactional(readOnly = true)
    public List<ConnectionRequest> pendingFor(User teacher) {
        return requests.findByTeacherAndStatusOrderByCreatedAtAsc(teacher, ConnectionStatus.PENDING);
    }
    @Transactional(readOnly = true)
    public List<User> studentsFor(User teacher) {
        return requests.findStudentsByTeacherAndStatus(teacher, ConnectionStatus.ACCEPTED);
    }
    @Transactional(readOnly = true)
    public List<ConnectionRequest> historyFor(User student) { return requests.findByStudentOrderByCreatedAtDesc(student); }
    @Transactional(readOnly = true)
    public boolean isAccepted(User student) { return requests.existsByStudentAndStatus(student, ConnectionStatus.ACCEPTED); }

    @Transactional(readOnly = true)
    public InviteState inviteState(User student, Long teacherId) {
        TeacherProfile profile = profiles.findByUserId(teacherId).orElseThrow(() -> new IllegalArgumentException("Преподаватель не найден"));
        User teacher = profile.getUser();
        if (requests.existsByStudentAndTeacherAndStatus(student, teacher, ConnectionStatus.ACCEPTED)) return InviteState.CONNECTED;
        if (requests.existsByStudentAndTeacherAndStatus(student, teacher, ConnectionStatus.PENDING)) return InviteState.PENDING;
        return InviteState.READY;
    }

    public enum InviteState { READY, PENDING, CONNECTED }
}

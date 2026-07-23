package ru.tutor.codeclass.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.tutor.codeclass.domain.*;
import ru.tutor.codeclass.repository.*;
import java.util.List;

@Service
public class ConnectionService {
    private final ConnectionRequestRepository requests;
    private final TeacherProfileRepository profiles;
    public ConnectionService(ConnectionRequestRepository requests, TeacherProfileRepository profiles) {
        this.requests = requests; this.profiles = profiles;
    }

    @Transactional
    public void send(User student, String code) {
        if (student.getRole() != Role.STUDENT) throw new IllegalArgumentException("Запрос может отправить только ученик");
        TeacherProfile profile = profiles.findByInviteCodeIgnoreCase(code.trim())
                .orElseThrow(() -> new IllegalArgumentException("Преподаватель с таким кодом не найден"));
        if (requests.existsByStudentAndStatus(student, ConnectionStatus.ACCEPTED))
            throw new IllegalArgumentException("Вы уже прикреплены к преподавателю");
        if (requests.existsByStudentAndTeacherAndStatus(student, profile.getUser(), ConnectionStatus.PENDING))
            throw new IllegalArgumentException("Запрос уже ожидает решения");
        requests.save(new ConnectionRequest(student, profile.getUser()));
    }

    @Transactional
    public void process(User teacher, Long requestId, boolean accept) {
        ConnectionRequest request = requests.findWithRelationsById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!request.getTeacher().getId().equals(teacher.getId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (request.getStatus() != ConnectionStatus.PENDING) throw new IllegalArgumentException("Запрос уже обработан");
        if (accept && requests.existsByStudentAndStatus(request.getStudent(), ConnectionStatus.ACCEPTED))
            throw new IllegalArgumentException("Ученик уже прикреплён к преподавателю");
        if (accept) request.accept(); else request.reject();
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
}

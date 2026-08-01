package ru.repethelper.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.repethelper.domain.ConnectionStatus;
import ru.repethelper.domain.LessonStatus;
import ru.repethelper.domain.User;
import ru.repethelper.repository.ConnectionRequestRepository;
import ru.repethelper.repository.WhiteboardRepository;

@Service
public class TeacherStudentBoardService {
    private static final int PAGE_SIZE = 20;
    private final ConnectionRequestRepository connections;
    private final WhiteboardRepository boards;

    public TeacherStudentBoardService(ConnectionRequestRepository connections, WhiteboardRepository boards) {
        this.connections = connections;
        this.boards = boards;
    }

    @Transactional(readOnly = true)
    public BoardHistory get(User teacher, Long studentId, int requestedPage) {
        if (teacher.getRole() != ru.repethelper.domain.Role.TEACHER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        var relation = connections.findByStudentIdAndTeacherAndStatus(studentId, teacher, ConnectionStatus.ACCEPTED)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var pageable = PageRequest.of(Math.max(0, requestedPage), PAGE_SIZE,
                Sort.by(Sort.Order.desc("lesson.startAt"), Sort.Order.desc("lesson.id")));
        var page = boards.findVisibleForTeacherAndStudent(teacher, relation.getStudent(), LessonStatus.CANCELLED, pageable);
        if (page.getTotalPages() > 0 && requestedPage >= page.getTotalPages()) {
            page = boards.findVisibleForTeacherAndStudent(teacher, relation.getStudent(), LessonStatus.CANCELLED,
                    PageRequest.of(page.getTotalPages() - 1, PAGE_SIZE,
                            Sort.by(Sort.Order.desc("lesson.startAt"), Sort.Order.desc("lesson.id"))));
        }
        return new BoardHistory(relation.getStudent(), page);
    }

    public record BoardHistory(User student, org.springframework.data.domain.Page<ru.repethelper.domain.Whiteboard> boards) {}
}

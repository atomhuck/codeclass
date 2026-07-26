package ru.repethelper.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.repethelper.domain.*;
import java.util.*;

public interface ConnectionRequestRepository extends JpaRepository<ConnectionRequest, Long> {
    boolean existsByStudentAndTeacherAndStatus(User student, User teacher, ConnectionStatus status);
    boolean existsByStudentAndStatus(User student, ConnectionStatus status);

    @EntityGraph(attributePaths = {"student", "teacher"})
    List<ConnectionRequest> findByTeacherAndStatusOrderByCreatedAtAsc(User teacher, ConnectionStatus status);

    @EntityGraph(attributePaths = {"student", "teacher"})
    List<ConnectionRequest> findByStudentOrderByCreatedAtDesc(User student);

    @EntityGraph(attributePaths = {"student", "teacher"})
    Optional<ConnectionRequest> findWithRelationsById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"student", "teacher"})
    Optional<ConnectionRequest> findLockedByStudentAndTeacherAndStatus(User student, User teacher, ConnectionStatus status);

    long deleteByStudentAndTeacher(User student, User teacher);

    @Query("select c.student from ConnectionRequest c where c.teacher = :teacher and c.status = :status order by c.student.displayName")
    List<User> findStudentsByTeacherAndStatus(@Param("teacher") User teacher, @Param("status") ConnectionStatus status);
}

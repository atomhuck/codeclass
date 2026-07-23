package ru.tutor.codeclass.repository;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import ru.tutor.codeclass.domain.*;
import java.util.*;

public interface WhiteboardObjectRepository extends JpaRepository<WhiteboardObject, UUID> {
    @EntityGraph(attributePaths = "createdBy")
    @Query("select o from WhiteboardObject o where o.board = :board order by o.zOrder asc")
    List<WhiteboardObject> findByBoardOrderByZOrderAsc(@Param("board") Whiteboard board);
    Optional<WhiteboardObject> findByIdAndBoard(UUID id, Whiteboard board);
    long countByBoard(Whiteboard board);

    @Query("select coalesce(max(o.zOrder), 0) from WhiteboardObject o where o.board = :board")
    long maxZOrder(@Param("board") Whiteboard board);

    void deleteByBoard(Whiteboard board);
}

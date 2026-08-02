package ru.repethelper.repository;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import ru.repethelper.domain.*;
import java.util.*;

public interface WhiteboardObjectRepository extends JpaRepository<WhiteboardObject, UUID> {
    @EntityGraph(attributePaths = "createdBy")
    @Query("select o from WhiteboardObject o where o.board = :board and o.deletedAt is null order by o.zOrder asc")
    List<WhiteboardObject> findByBoardOrderByZOrderAsc(@Param("board") Whiteboard board);
    @EntityGraph(attributePaths = {"createdBy", "deletedBy"})
    Optional<WhiteboardObject> findByIdAndBoard(UUID id, Whiteboard board);
    long countByBoardAndDeletedAtIsNull(Whiteboard board);
    long countByBoardAndDeletedAtIsNotNull(Whiteboard board);

    @Query("select coalesce(max(o.zOrder), 0) from WhiteboardObject o where o.board = :board")
    long maxZOrder(@Param("board") Whiteboard board);

    void deleteByBoard(Whiteboard board);

    @EntityGraph(attributePaths = {"board", "createdBy", "deletedBy"})
    List<WhiteboardObject> findTop600ByBoardAndDeletedAtIsNotNullOrderByDeletedAtAsc(Whiteboard board);

    @EntityGraph(attributePaths = {"board", "createdBy", "deletedBy"})
    List<WhiteboardObject> findByDeletedAtBefore(java.time.Instant threshold);
}

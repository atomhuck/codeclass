package ru.tutor.codeclass.repository;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import ru.tutor.codeclass.domain.*;
import java.util.*;

public interface WhiteboardImageRepository extends JpaRepository<WhiteboardImage, UUID> {
    @EntityGraph(attributePaths = {"object", "object.board", "object.board.lesson", "object.board.lesson.student"})
    @Query("select i from WhiteboardImage i where i.object.id = :objectId")
    Optional<WhiteboardImage> findWithBoardByObjectId(@Param("objectId") UUID objectId);

    @Query("select i from WhiteboardImage i where i.object.board = :board")
    List<WhiteboardImage> findByBoard(@Param("board") Whiteboard board);

    @Query("select count(i) from WhiteboardImage i where i.object.board = :board")
    long countByBoard(@Param("board") Whiteboard board);

    @Query("select coalesce(sum(i.sizeBytes), 0) from WhiteboardImage i where i.object.board = :board")
    long totalSizeByBoard(@Param("board") Whiteboard board);

    @Query("select i from WhiteboardImage i where i.object.board.lesson in :lessons")
    List<WhiteboardImage> findByLessons(@Param("lessons") Collection<Lesson> lessons);
}

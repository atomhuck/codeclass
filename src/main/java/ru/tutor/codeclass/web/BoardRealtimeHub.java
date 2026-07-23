package ru.tutor.codeclass.web;

import tools.jackson.databind.*;
import tools.jackson.core.JacksonException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

@Component
public class BoardRealtimeHub {
    private final ObjectMapper mapper;
    private final Map<UUID, ConcurrentMap<String, WebSocketSession>> sessions = new ConcurrentHashMap<>();

    public BoardRealtimeHub(ObjectMapper mapper) { this.mapper = mapper; }

    public void join(UUID boardId, WebSocketSession session) {
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, 5_000, 256 * 1024);
        sessions.computeIfAbsent(boardId, ignored -> new ConcurrentHashMap<>()).put(session.getId(), safe);
    }

    public void leave(UUID boardId, String sessionId) {
        var boardSessions = sessions.get(boardId);
        if (boardSessions == null) return;
        boardSessions.remove(sessionId);
        if (boardSessions.isEmpty()) sessions.remove(boardId, boardSessions);
    }

    public int participantCount(UUID boardId) {
        var boardSessions = sessions.get(boardId);
        return boardSessions == null ? 0 : boardSessions.size();
    }

    public void broadcast(UUID boardId, JsonNode payload, String excludedSessionId) {
        var boardSessions = sessions.get(boardId);
        if (boardSessions == null) return;
        final String text;
        try { text = mapper.writeValueAsString(payload); }
        catch (JacksonException ex) { throw new IllegalStateException("Не удалось сериализовать событие доски", ex); }
        boardSessions.forEach((id, session) -> {
            if (Objects.equals(id, excludedSessionId) || !session.isOpen()) return;
            try { session.sendMessage(new TextMessage(text)); }
            catch (IOException ex) {
                try { session.close(CloseStatus.SERVER_ERROR); } catch (IOException ignored) {}
                leave(boardId, id);
            }
        });
    }

    public void send(UUID boardId, String sessionId, JsonNode payload) throws IOException {
        var boardSessions = sessions.get(boardId);
        WebSocketSession session = boardSessions == null ? null : boardSessions.get(sessionId);
        if (session != null && session.isOpen())
            session.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
    }
}

package ru.tutor.codeclass.web;

import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.core.JacksonException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import ru.tutor.codeclass.domain.User;
import ru.tutor.codeclass.service.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Component
public class WhiteboardWebSocketHandler extends TextWebSocketHandler {
    private static final int MAX_MESSAGE_SIZE = 128 * 1024;
    private static final double MAX_COORDINATE = 1_000_000d;
    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private final ObjectMapper mapper;
    private final AccountService accounts;
    private final WhiteboardService boards;
    private final BoardRealtimeHub hub;
    private final Map<String, RateWindow> rates = new ConcurrentHashMap<>();

    public WhiteboardWebSocketHandler(ObjectMapper mapper, AccountService accounts,
                                      WhiteboardService boards, BoardRealtimeHub hub) {
        this.mapper = mapper; this.accounts = accounts; this.boards = boards; this.hub = hub;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (session.getPrincipal() == null) { session.close(CloseStatus.NOT_ACCEPTABLE); return; }
        UUID boardId = boardId(session);
        User user = accounts.requireByUsername(session.getPrincipal().getName());
        try { boards.requireAccessible(user, boardId); }
        catch (ResponseStatusException ex) { session.close(CloseStatus.POLICY_VIOLATION); return; }
        session.getAttributes().put("displayName", user.getDisplayName());
        session.getAttributes().put("userId", user.getId());
        hub.join(boardId, session);
        ObjectNode event = base("presence.join", session, user);
        event.put("participants", hub.participantCount(boardId));
        hub.broadcast(boardId, event, null);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (message.getPayloadLength() > MAX_MESSAGE_SIZE) {
            error(session, "Сообщение слишком большое"); return;
        }
        if (!allow(session.getId())) {
            error(session, "Слишком много сообщений"); return;
        }
        UUID boardId = boardId(session);
        User user = accounts.requireByUsername(session.getPrincipal().getName());
        JsonNode incoming;
        try { incoming = mapper.readTree(message.getPayload()); }
        catch (JacksonException ex) { error(session, "Некорректное сообщение"); return; }
        String type = incoming.path("type").asText();
        try {
            switch (type) {
                case "cursor.move", "stroke.begin", "stroke.points" -> {
                    validateEphemeral(type, incoming);
                    relayEphemeral(boardId, session, user, incoming);
                }
                case "stroke.commit" -> {
                    UUID objectId = UUID.fromString(incoming.path("objectId").asText());
                    var result = boards.createPath(user, boardId, objectId, incoming.path("data"));
                    if (result.changed()) hub.broadcast(boardId, objectEvent("object.created", result), null);
                    else hub.send(boardId, session.getId(), objectEvent("object.created", result));
                }
                case "object.update" -> {
                    UUID objectId = UUID.fromString(incoming.path("objectId").asText());
                    long version = incoming.path("expectedVersion").asLong(-1);
                    var result = boards.updateObject(user, boardId, objectId, version, incoming.path("data"));
                    hub.broadcast(boardId, objectEvent("object.updated", result), null);
                }
                case "object.delete" -> {
                    UUID objectId = UUID.fromString(incoming.path("objectId").asText());
                    var result = boards.deleteObject(user, boardId, objectId);
                    if (result.changed()) {
                        ObjectNode event = mapper.createObjectNode();
                        event.put("type", "object.deleted"); event.put("revision", result.revision());
                        event.put("objectId", result.objectId().toString());
                        hub.broadcast(boardId, event, null);
                    }
                }
                case "board.clear" -> {
                    var result = boards.clear(user, boardId);
                    ObjectNode event = mapper.createObjectNode();
                    event.put("type", "board.cleared"); event.put("revision", result.revision());
                    hub.broadcast(boardId, event, null);
                }
                default -> error(session, "Неизвестный тип события");
            }
        } catch (WhiteboardService.VersionConflictException ex) {
            ObjectNode event = mapper.createObjectNode();
            event.put("type", "sync.required");
            event.set("object", mapper.valueToTree(ex.getCurrent()));
            hub.send(boardId, session.getId(), event);
        } catch (IllegalArgumentException ex) {
            error(session, ex.getMessage());
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode() == HttpStatus.FORBIDDEN) session.close(CloseStatus.POLICY_VIOLATION);
            else error(session, "Объект не найден");
        }
    }

    private void relayEphemeral(UUID boardId, WebSocketSession session, User user, JsonNode incoming) {
        ObjectNode event = (ObjectNode) incoming.deepCopy();
        event.put("actorId", user.getId());
        event.put("actorName", user.getDisplayName());
        event.put("sessionId", session.getId());
        hub.broadcast(boardId, event, session.getId());
    }

    private void validateEphemeral(String type, JsonNode event) {
        if (type.equals("cursor.move")) {
            coordinate(event.path("x")); coordinate(event.path("y"));
            return;
        }
        String strokeId = event.path("strokeId").asText();
        if (strokeId.isBlank() || strokeId.length() > 80)
            throw new IllegalArgumentException("Некорректный идентификатор штриха");
        if (type.equals("stroke.begin")) {
            String color = event.path("color").asText();
            double width = event.path("width").asDouble(Double.NaN);
            if (!COLOR.matcher(color).matches() || !Double.isFinite(width) || width < 1 || width > 40)
                throw new IllegalArgumentException("Некорректные параметры штриха");
            point(event.path("point"));
            return;
        }
        JsonNode points = event.path("points");
        if (!points.isArray() || points.isEmpty() || points.size() > 100)
            throw new IllegalArgumentException("Некорректные точки штриха");
        points.forEach(this::point);
    }

    private void point(JsonNode point) {
        if (!point.isObject()) throw new IllegalArgumentException("Некорректные точки штриха");
        coordinate(point.path("x")); coordinate(point.path("y"));
    }

    private void coordinate(JsonNode node) {
        double value = node.asDouble(Double.NaN);
        if (!Double.isFinite(value) || Math.abs(value) > MAX_COORDINATE)
            throw new IllegalArgumentException("Некорректные координаты");
    }

    private ObjectNode objectEvent(String type, WhiteboardService.MutationResult result) {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", type); event.put("revision", result.revision());
        event.set("object", mapper.valueToTree(result.object()));
        return event;
    }

    private ObjectNode base(String type, WebSocketSession session, User user) {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", type); event.put("actorId", user.getId());
        event.put("actorName", user.getDisplayName()); event.put("sessionId", session.getId());
        return event;
    }

    private void error(WebSocketSession session, String message) throws IOException {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", "error"); event.put("message", message);
        hub.send(boardId(session), session.getId(), event);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID boardId;
        try { boardId = boardId(session); } catch (RuntimeException ex) { return; }
        hub.leave(boardId, session.getId());
        rates.remove(session.getId());
        ObjectNode event = mapper.createObjectNode();
        event.put("type", "presence.leave");
        event.put("sessionId", session.getId());
        Object name = session.getAttributes().get("displayName");
        event.put("actorName", name == null ? "Участник" : name.toString());
        event.put("participants", hub.participantCount(boardId));
        hub.broadcast(boardId, event, null);
    }

    private UUID boardId(WebSocketSession session) {
        return (UUID) session.getAttributes().get("boardId");
    }

    private boolean allow(String sessionId) {
        long second = Instant.now().getEpochSecond();
        RateWindow window = rates.computeIfAbsent(sessionId, ignored -> new RateWindow(second, 0));
        synchronized (window) {
            if (window.second != second) { window.second = second; window.count = 0; }
            return ++window.count <= 60;
        }
    }

    private static class RateWindow {
        long second; int count;
        RateWindow(long second, int count) { this.second = second; this.count = count; }
    }
}

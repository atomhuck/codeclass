package ru.repethelper.web;

import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.core.JacksonException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import ru.repethelper.domain.User;
import ru.repethelper.security.RepetHelperPrincipal;
import ru.repethelper.service.*;
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
        if (!validSession(session, user)) { session.close(CloseStatus.POLICY_VIOLATION); return; }
        try { boards.requireAccessible(user, boardId); }
        catch (ResponseStatusException ex) { session.close(CloseStatus.POLICY_VIOLATION); return; }
        session.getAttributes().put("displayName", user.getDisplayName());
        session.getAttributes().put("userId", user.getId());
        hub.join(boardId, session);
        ObjectNode self = base("presence.self", session, user);
        self.put("participants", hub.participantCount(boardId));
        hub.send(boardId, session.getId(), self);
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
        if (!validSession(session, user)) { session.close(CloseStatus.POLICY_VIOLATION); return; }
        JsonNode incoming;
        try { incoming = mapper.readTree(message.getPayload()); }
        catch (JacksonException ex) { error(session, "Некорректное сообщение"); return; }
        String type = incoming.path("type").asText();
        UUID operationId = null;
        try {
            if (Set.of("stroke.commit", "text.commit", "object.update", "object.delete", "objects.move",
                    "objects.delete", "objects.restore", "board.clear").contains(type)) {
                operationId = UUID.fromString(incoming.path("operationId").asText());
            }
            switch (type) {
                case "cursor.move", "stroke.begin", "stroke.points" -> {
                    validateEphemeral(type, incoming);
                    relayEphemeral(boardId, session, user, incoming);
                }
                case "stroke.commit" -> {
                    UUID objectId = UUID.fromString(incoming.path("objectId").asText());
                    var result = boards.createPath(user, boardId, objectId, incoming.path("data"));
                    if (result.changed()) hub.broadcast(boardId, objectEvent("object.created", result, operationId, session), null);
                    else hub.send(boardId, session.getId(), objectEvent("object.created", result, operationId, session));
                }
                case "text.commit" -> {
                    UUID objectId = UUID.fromString(incoming.path("objectId").asText());
                    var result = boards.createText(user, boardId, objectId, incoming.path("data"));
                    if (result.changed()) hub.broadcast(boardId, objectEvent("object.created", result, operationId, session), null);
                    else hub.send(boardId, session.getId(), objectEvent("object.created", result, operationId, session));
                }
                case "object.update" -> {
                    UUID objectId = UUID.fromString(incoming.path("objectId").asText());
                    long version = incoming.path("expectedVersion").asLong(-1);
                    var result = boards.updateObject(user, boardId, objectId, version, incoming.path("data"));
                    hub.broadcast(boardId, objectEvent("object.updated", result, operationId, session), null);
                }
                case "object.delete" -> {
                    UUID objectId = UUID.fromString(incoming.path("objectId").asText());
                    var result = boards.deleteObject(user, boardId, objectId, operationId);
                    if (result.changed()) {
                        ObjectNode event = mapper.createObjectNode();
                        event.put("type", "object.deleted"); event.put("revision", result.revision());
                        event.put("objectId", result.objectId().toString());
                        operation(event, operationId, session);
                        hub.broadcast(boardId, event, null);
                    }
                }
                case "objects.move" -> {
                    var result = boards.moveObjects(user, boardId, versioned(incoming.path("objects")),
                            incoming.path("deltaX").asDouble(Double.NaN),
                            incoming.path("deltaY").asDouble(Double.NaN));
                    hub.broadcast(boardId, batchEvent("objects.updated", result, operationId, session), null);
                }
                case "objects.delete" -> {
                    var result = boards.deleteObjects(user, boardId, operationId, ids(incoming.path("objectIds")));
                    if (result.changed()) hub.broadcast(boardId,
                            batchEvent("objects.deleted", result, operationId, session), null);
                }
                case "objects.restore" -> {
                    UUID deleteOperationId = UUID.fromString(incoming.path("deleteOperationId").asText());
                    var result = boards.restoreObjects(user, boardId, deleteOperationId,
                            versioned(incoming.path("objects")));
                    hub.broadcast(boardId, batchEvent("objects.restored", result, operationId, session), null);
                }
                case "board.clear" -> {
                    var result = boards.clear(user, boardId);
                    ObjectNode event = mapper.createObjectNode();
                    event.put("type", "board.cleared"); event.put("revision", result.revision());
                    operation(event, operationId, session);
                    hub.broadcast(boardId, event, null);
                }
                default -> error(session, "Неизвестный тип события");
            }
        } catch (WhiteboardService.VersionConflictException ex) {
            ObjectNode event = mapper.createObjectNode();
            event.put("type", "sync.required"); event.put("code", "VERSION_CONFLICT");
            if (operationId != null) event.put("operationId", operationId.toString());
            event.set("object", mapper.valueToTree(ex.getCurrent()));
            hub.send(boardId, session.getId(), event);
        } catch (WhiteboardService.UndoExpiredException ex) {
            error(session, "UNDO_EXPIRED", ex.getMessage(), operationId);
        } catch (IllegalArgumentException ex) {
            String code = ex.getMessage() != null && ex.getMessage().toLowerCase(Locale.ROOT).contains("координат")
                    ? "COORDINATES_OUT_OF_RANGE" : "INVALID_OPERATION";
            error(session, code, ex.getMessage(), operationId);
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode() == HttpStatus.FORBIDDEN) session.close(CloseStatus.POLICY_VIOLATION);
            else error(session, "NOT_FOUND", "Объект не найден", operationId);
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

    private ObjectNode objectEvent(String type, WhiteboardService.MutationResult result,
                                   UUID operationId, WebSocketSession session) {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", type); event.put("revision", result.revision());
        event.set("object", mapper.valueToTree(result.object()));
        operation(event, operationId, session);
        return event;
    }

    private ObjectNode batchEvent(String type, WhiteboardService.BatchMutationResult result,
                                  UUID operationId, WebSocketSession session) {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", type); event.put("revision", result.revision());
        event.set("objects", mapper.valueToTree(result.objects()));
        operation(event, operationId, session);
        return event;
    }

    private void operation(ObjectNode event, UUID operationId, WebSocketSession session) {
        if (operationId != null) event.put("operationId", operationId.toString());
        event.put("actorSessionId", session.getId());
    }

    private List<WhiteboardService.VersionedObject> versioned(JsonNode value) {
        if (!value.isArray() || value.isEmpty() || value.size() > WhiteboardService.MAX_BATCH_OBJECTS)
            throw new IllegalArgumentException("Некорректный список объектов");
        List<WhiteboardService.VersionedObject> result = new ArrayList<>();
        for (JsonNode item : value) {
            result.add(new WhiteboardService.VersionedObject(
                    UUID.fromString(item.path("id").asText()), item.path("expectedVersion").asLong(-1)));
        }
        return result;
    }

    private List<UUID> ids(JsonNode value) {
        if (!value.isArray() || value.isEmpty() || value.size() > WhiteboardService.MAX_BATCH_OBJECTS)
            throw new IllegalArgumentException("Некорректный список объектов");
        List<UUID> result = new ArrayList<>();
        value.forEach(item -> result.add(UUID.fromString(item.asText())));
        return result;
    }

    private ObjectNode base(String type, WebSocketSession session, User user) {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", type); event.put("actorId", user.getId());
        event.put("actorName", user.getDisplayName()); event.put("sessionId", session.getId());
        return event;
    }

    private void error(WebSocketSession session, String message) throws IOException {
        error(session, "INVALID_MESSAGE", message, null);
    }

    private void error(WebSocketSession session, String code, String message, UUID operationId) throws IOException {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", "operation.rejected"); event.put("code", code); event.put("message", message);
        if (operationId != null) event.put("operationId", operationId.toString());
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

    private boolean validSession(WebSocketSession session, User user) {
        return user.isEnabled() && session.getPrincipal() instanceof org.springframework.security.core.Authentication auth
                && auth.getPrincipal() instanceof RepetHelperPrincipal principal
                && principal.authVersion() == user.getAuthVersion();
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

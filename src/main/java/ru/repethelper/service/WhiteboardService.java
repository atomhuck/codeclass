package ru.repethelper.service;

import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.core.io.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;
import ru.repethelper.domain.*;
import ru.repethelper.repository.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class WhiteboardService {
    public static final long MAX_IMAGE_SIZE = 10L * 1024 * 1024;
    public static final int MAX_IMAGES = 30;
    public static final long MAX_BOARD_IMAGE_SIZE = 150L * 1024 * 1024;
    public static final int MAX_OBJECTS = 5_000;
    public static final int MAX_PATH_VALUES = 25_000;
    public static final int MAX_BATCH_OBJECTS = 500;
    public static final int MAX_DELETED_OBJECTS = 500;
    public static final long MAX_DELETED_IMAGE_SIZE = 150L * 1024 * 1024;
    public static final int MAX_TEXT_LENGTH = 2_000;
    public static final int MAX_TEXT_LINES = 50;
    public static final int MAX_TEXT_STYLE_ENTRIES = 2_000;
    public static final int MIN_FONT_SIZE = 12;
    public static final int MAX_FONT_SIZE = 144;
    public static final java.time.Duration DELETED_RETENTION = java.time.Duration.ofHours(2);
    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final double MAX_COORDINATE = 1_000_000d;
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter DEFAULT_BOARD_NAME = DateTimeFormatter
            .ofPattern("d MMMM, HH:mm", Locale.forLanguageTag("ru-RU"));

    private final WhiteboardRepository boards;
    private final WhiteboardObjectRepository objects;
    private final WhiteboardImageRepository images;
    private final UserRepository users;
    private final ObjectMapper mapper;
    private final Path root;

    public WhiteboardService(WhiteboardRepository boards, WhiteboardObjectRepository objects,
                             WhiteboardImageRepository images, UserRepository users, ObjectMapper mapper,
                             @org.springframework.beans.factory.annotation.Value("${app.storage-path}") String storagePath) {
        this.boards = boards;
        this.objects = objects;
        this.images = images;
        this.users = users;
        this.mapper = mapper;
        this.root = Paths.get(storagePath).toAbsolutePath().normalize().resolve("boards");
        try { Files.createDirectories(root); }
        catch (IOException ex) { throw new IllegalStateException("Не удалось подготовить хранилище досок", ex); }
    }

    @Transactional
    public Whiteboard getOrCreate(User user, Lesson lesson) {
        requireAccess(user, lesson);
        return boards.findByLesson(lesson).orElseGet(() -> boards.save(new Whiteboard(lesson)));
    }

    @Transactional(readOnly = true)
    public Whiteboard requireAccessible(User user, UUID publicId) {
        Whiteboard board = boards.findByPublicId(publicId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        requireAccess(user, board.getLesson());
        return board;
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot(User user, UUID publicId) {
        Whiteboard board = requireAccessible(user, publicId);
        List<ObjectView> views = objects.findByBoardOrderByZOrderAsc(board).stream()
                .map(item -> view(item, publicId)).toList();
        return new Snapshot(publicId, board.getRevision(), board.getLesson().getId(),
                board.getLesson().getStudent().getDisplayName(), displayName(board), views);
    }

    @Transactional
    public BoardMetadata rename(User user, UUID publicId, String requestedName) {
        Whiteboard board = locked(user, publicId);
        if (user.getRole() != Role.TEACHER || !board.getLesson().getTeacher().getId().equals(user.getId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        String name = requestedName == null ? null : requestedName.strip();
        if (name != null && name.length() > 120)
            throw new IllegalArgumentException("Название не может быть длиннее 120 символов");
        board.rename(name);
        return metadata(board);
    }

    @Transactional(readOnly = true)
    public BoardMetadata metadata(User user, UUID publicId) {
        return metadata(requireAccessible(user, publicId));
    }

    public String displayName(Whiteboard board) {
        if (board.getCustomName() != null && !board.getCustomName().isBlank()) return board.getCustomName();
        return "Доска · " + DEFAULT_BOARD_NAME.format(board.getLesson().getStartAt().atZone(MOSCOW));
    }

    private BoardMetadata metadata(Whiteboard board) {
        return new BoardMetadata(board.getPublicId(), board.getLesson().getId(), displayName(board),
                board.getLesson().getStartAt(), board.getLesson().getDurationMinutes());
    }

    @Transactional
    public MutationResult createPath(User user, UUID publicId, UUID objectId, JsonNode data) {
        Whiteboard board = locked(user, publicId);
        Optional<WhiteboardObject> duplicate = objects.findById(objectId);
        if (duplicate.isPresent()) return duplicateCreate(board, publicId, duplicate.get(), WhiteboardObjectType.PATH);
        requireObjectCapacity(board);
        validatePath(data);
        WhiteboardObject item = objects.save(new WhiteboardObject(objectId, board, WhiteboardObjectType.PATH,
                json(data), objects.maxZOrder(board) + 1, user));
        long revision = board.nextRevision();
        return new MutationResult(revision, view(item, publicId), true);
    }

    @Transactional
    public MutationResult createText(User user, UUID publicId, UUID objectId, JsonNode data) {
        Whiteboard board = locked(user, publicId);
        Optional<WhiteboardObject> duplicate = objects.findById(objectId);
        if (duplicate.isPresent()) return duplicateCreate(board, publicId, duplicate.get(), WhiteboardObjectType.TEXT);
        requireObjectCapacity(board);
        validateText(data);
        WhiteboardObject item = objects.save(new WhiteboardObject(objectId, board, WhiteboardObjectType.TEXT,
                json(data), objects.maxZOrder(board) + 1, user));
        long revision = board.nextRevision();
        return new MutationResult(revision, view(item, publicId), true);
    }

    @Transactional
    public MutationResult updateObject(User user, UUID publicId, UUID objectId, long expectedVersion, JsonNode data) {
        Whiteboard board = locked(user, publicId);
        WhiteboardObject item = objects.findByIdAndBoard(objectId, board)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (item.isDeleted()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (item.getVersion() != expectedVersion) throw new VersionConflictException(view(item, publicId));
        validateObjectData(item.getType(), data);
        item.update(json(data));
        long revision = board.nextRevision();
        return new MutationResult(revision, view(item, publicId), true);
    }

    @Transactional
    public DeleteResult deleteObject(User user, UUID publicId, UUID objectId) {
        return deleteObject(user, publicId, objectId, UUID.randomUUID());
    }

    @Transactional
    public DeleteResult deleteObject(User user, UUID publicId, UUID objectId, UUID operationId) {
        Whiteboard board = locked(user, publicId);
        Optional<WhiteboardObject> found = objects.findByIdAndBoard(objectId, board);
        if (found.isEmpty() || found.get().isDeleted())
            return new DeleteResult(board.getRevision(), objectId, false, null);
        WhiteboardObject item = found.get();
        item.softDelete(user, operationId);
        long revision = board.nextRevision();
        purgeExcessDeleted(board);
        return new DeleteResult(revision, objectId, true, null);
    }

    @Transactional
    public BatchMutationResult deleteObjects(User user, UUID publicId, UUID operationId, List<UUID> objectIds) {
        Whiteboard board = locked(user, publicId);
        List<UUID> ids = normalizedIds(objectIds);
        List<WhiteboardObject> changed = new ArrayList<>();
        for (UUID id : ids) {
            WhiteboardObject item = objects.findByIdAndBoard(id, board)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            if (item.isDeleted()) continue;
            item.softDelete(user, operationId);
            changed.add(item);
        }
        if (changed.isEmpty()) return new BatchMutationResult(board.getRevision(), List.of(), false);
        long revision = board.nextRevision();
        List<ObjectView> views = changed.stream().map(item -> view(item, publicId)).toList();
        purgeExcessDeleted(board);
        return new BatchMutationResult(revision, views, true);
    }

    @Transactional
    public BatchMutationResult restoreObjects(User user, UUID publicId, UUID deleteOperationId,
                                              List<VersionedObject> requested) {
        Whiteboard board = locked(user, publicId);
        if (requested == null || requested.isEmpty() || requested.size() > MAX_BATCH_OBJECTS)
            throw new IllegalArgumentException("Некорректный список объектов");
        List<WhiteboardObject> restored = new ArrayList<>();
        Instant cutoff = Instant.now().minus(DELETED_RETENTION);
        for (VersionedObject request : requested) {
            WhiteboardObject item = objects.findByIdAndBoard(request.id(), board)
                    .orElseThrow(() -> new UndoExpiredException());
            if (!item.isDeleted() || item.getDeletedAt().isBefore(cutoff)) throw new UndoExpiredException();
            if (item.getVersion() != request.expectedVersion()) throw new VersionConflictException(view(item, publicId));
            item.restore(user, deleteOperationId);
            restored.add(item);
        }
        long revision = board.nextRevision();
        return new BatchMutationResult(revision, restored.stream().map(item -> view(item, publicId)).toList(), true);
    }

    @Transactional
    public BatchMutationResult moveObjects(User user, UUID publicId, List<VersionedObject> requested,
                                           double deltaX, double deltaY) {
        requireCoordinate(deltaX); requireCoordinate(deltaY);
        Whiteboard board = locked(user, publicId);
        if (requested == null || requested.isEmpty() || requested.size() > MAX_BATCH_OBJECTS)
            throw new IllegalArgumentException("За один раз можно переместить не более 500 объектов");
        List<WhiteboardObject> changed = new ArrayList<>();
        for (VersionedObject request : requested) {
            WhiteboardObject item = objects.findByIdAndBoard(request.id(), board)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            if (item.isDeleted()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            if (item.getVersion() != request.expectedVersion()) throw new VersionConflictException(view(item, publicId));
            ObjectNode data = (ObjectNode) parse(item.getData()).deepCopy();
            double left = data.path("left").asDouble(0) + deltaX;
            double top = data.path("top").asDouble(0) + deltaY;
            requireCoordinate(left); requireCoordinate(top);
            data.put("left", left); data.put("top", top);
            validateObjectData(item.getType(), data);
            item.update(json(data));
            changed.add(item);
        }
        long revision = board.nextRevision();
        return new BatchMutationResult(revision, changed.stream().map(item -> view(item, publicId)).toList(), true);
    }

    @Transactional
    public ClearResult clear(User user, UUID publicId) {
        Whiteboard board = locked(user, publicId);
        if (user.getRole() != Role.TEACHER) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        List<WhiteboardImage> boardImages = images.findByBoard(board);
        List<String> storedNames = boardImages.stream().map(WhiteboardImage::getStoredName).toList();
        images.deleteAll(boardImages);
        if (!boardImages.isEmpty()) images.flush();
        objects.deleteByBoard(board);
        objects.flush();
        long revision = board.nextRevision();
        deletePhysicalAfterCommit(storedNames);
        return new ClearResult(revision);
    }

    @Transactional
    public MutationResult uploadImage(User user, UUID publicId, MultipartFile file, double left, double top) {
        Whiteboard board = locked(user, publicId);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Выберите изображение");
        if (file.getSize() > MAX_IMAGE_SIZE) throw new IllegalArgumentException("Изображение превышает 10 МБ");
        if (images.countByBoard(board) >= MAX_IMAGES) throw new IllegalArgumentException("На доске может быть не более 30 изображений");
        if (images.totalSizeByBoard(board) + file.getSize() > MAX_BOARD_IMAGE_SIZE)
            throw new IllegalArgumentException("Изображения на доске превышают общий лимит 150 МБ");
        requireObjectCapacity(board);
        requireCoordinate(left); requireCoordinate(top);

        ProcessedImage processed = processImage(file);
        if (images.totalSizeByBoard(board) + processed.bytes().length > MAX_BOARD_IMAGE_SIZE)
            throw new IllegalArgumentException("Изображения на доске превышают общий лимит 150 МБ");
        UUID objectId = UUID.randomUUID();
        String storedName = objectId + processed.extension();
        Path target = safePath(storedName);
        try { Files.write(target, processed.bytes(), StandardOpenOption.CREATE_NEW); }
        catch (IOException ex) { throw new IllegalStateException("Не удалось сохранить изображение", ex); }

        deletePhysicalAfterRollback(storedName);
        try {
            ObjectNode data = mapper.createObjectNode();
            data.put("left", left); data.put("top", top);
            data.put("width", processed.width()); data.put("height", processed.height());
            data.put("scaleX", Math.min(1d, 640d / processed.width()));
            data.put("scaleY", Math.min(1d, 640d / processed.width()));
            data.put("angle", 0);
            WhiteboardObject item = objects.save(new WhiteboardObject(objectId, board, WhiteboardObjectType.IMAGE,
                    json(data), objects.maxZOrder(board) + 1, user));
            String original = Optional.ofNullable(file.getOriginalFilename()).orElse("image");
            original = Paths.get(original).getFileName().toString();
            if (original.length() > 255) original = original.substring(original.length() - 255);
            images.save(new WhiteboardImage(item, original, storedName, processed.contentType(),
                    processed.bytes().length, processed.width(), processed.height()));
            long revision = board.nextRevision();
            return new MutationResult(revision, view(item, publicId), true);
        } catch (RuntimeException ex) {
            deletePhysical(storedName);
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public ImageDownload loadImage(User user, UUID publicId, UUID objectId) {
        Whiteboard board = requireAccessible(user, publicId);
        WhiteboardImage image = images.findWithBoardByObjectId(objectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!image.getObject().getBoard().getId().equals(board.getId()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (image.getObject().isDeleted()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Path file = safePath(image.getStoredName());
        if (!Files.isRegularFile(file)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return new ImageDownload(image, new FileSystemResource(file));
    }

    public List<String> storedImagesForLessons(Collection<Lesson> lessons) {
        if (lessons.isEmpty()) return List.of();
        return images.findByLessons(lessons).stream().map(WhiteboardImage::getStoredName).toList();
    }

    @Transactional(readOnly = true)
    public List<UUID> publicIdsForLessons(Collection<Lesson> lessons) {
        if (lessons.isEmpty()) return List.of();
        return boards.findPublicIdsByLessonIn(lessons);
    }

    /** Deletes board records before their lessons, so Hibernate cannot retain an invalid reference. */
    public void deleteBoardsForLessons(Collection<Lesson> lessons) {
        if (!lessons.isEmpty()) boards.deleteByLessonIn(lessons);
    }

    public void deleteStoredImages(Collection<String> names) { deletePhysicalAfterCommit(names); }

    @EventListener(ApplicationReadyEvent.class)
    public void removeOrphanedImageFiles() {
        Set<String> referenced = new HashSet<>();
        images.findAll().forEach(image -> referenced.add(image.getStoredName()));
        try (var files = Files.list(root)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> !referenced.contains(path.getFileName().toString()))
                    .forEach(path -> {
                        try { Files.deleteIfExists(path); }
                        catch (IOException ignored) { /* следующая проверка запуска повторит очистку */ }
                    });
        } catch (IOException ignored) {
            /* недоступность каталога не должна мешать запуску приложения */
        }
    }

    private Whiteboard locked(User user, UUID publicId) {
        Whiteboard board = boards.findLockedByPublicId(publicId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        requireAccess(user, board.getLesson());
        return board;
    }

    private void requireAccess(User user, Lesson lesson) {
        if (user.getRole() == Role.TEACHER && lesson.getTeacher().getId().equals(user.getId())) return;
        if (user.getRole() != Role.STUDENT || !lesson.getStudent().getId().equals(user.getId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private void validatePath(JsonNode data) {
        if (data == null || !data.isObject()) throw new IllegalArgumentException("Некорректный штрих");
        String color = data.path("stroke").asText();
        double width = data.path("strokeWidth").asDouble(Double.NaN);
        if (!COLOR.matcher(color).matches()) throw new IllegalArgumentException("Некорректный цвет");
        if (!Double.isFinite(width) || width < 1 || width > 40) throw new IllegalArgumentException("Некорректная толщина");
        JsonNode path = data.path("path");
        if (!path.isArray() || path.isEmpty() || path.size() > 5_000) throw new IllegalArgumentException("Штрих слишком большой");
        int[] count = {0};
        validateNumbers(path, count);
        if (count[0] > MAX_PATH_VALUES) throw new IllegalArgumentException("Штрих слишком большой");
    }

    private void validateText(JsonNode data) {
        if (data == null || !data.isObject()) throw new IllegalArgumentException("Некорректный текст");
        String text = data.path("text").asText();
        if (text.isBlank()) throw new IllegalArgumentException("Введите текст");
        if (text.codePointCount(0, text.length()) > MAX_TEXT_LENGTH)
            throw new IllegalArgumentException("Текст не может быть длиннее 2000 символов");
        if (text.lines().count() > MAX_TEXT_LINES)
            throw new IllegalArgumentException("В тексте может быть не более 50 строк");
        String color = data.path("fill").asText();
        if (!COLOR.matcher(color).matches()) throw new IllegalArgumentException("Некорректный цвет текста");
        double fontSize = data.path("fontSize").asDouble(Double.NaN);
        if (!Double.isFinite(fontSize) || fontSize < MIN_FONT_SIZE || fontSize > MAX_FONT_SIZE)
            throw new IllegalArgumentException("Размер текста должен быть от 12 до 144 px");
        validateTextStyles(data.path("styles"), text);
        requireCoordinate(data.path("left").asDouble(Double.NaN));
        requireCoordinate(data.path("top").asDouble(Double.NaN));
    }

    private void validateTextStyles(JsonNode styles, String text) {
        if (styles == null || styles.isMissingNode() || styles.isNull()) return;
        if (!styles.isObject()) throw new IllegalArgumentException("Некорректное форматирование текста");
        String[] lines = text.split("\\R", -1);
        int entries = 0;
        Iterator<Map.Entry<String, JsonNode>> lineFields = styles.properties().iterator();
        while (lineFields.hasNext()) {
            Map.Entry<String, JsonNode> lineEntry = lineFields.next();
            int line = numericStyleIndex(lineEntry.getKey());
            if (line < 0 || line >= lines.length || !lineEntry.getValue().isObject())
                throw new IllegalArgumentException("Некорректное форматирование текста");
            Iterator<Map.Entry<String, JsonNode>> characterFields = lineEntry.getValue().properties().iterator();
            while (characterFields.hasNext()) {
                Map.Entry<String, JsonNode> characterEntry = characterFields.next();
                int character = numericStyleIndex(characterEntry.getKey());
                JsonNode style = characterEntry.getValue();
                if (character < 0 || character >= lines[line].length() || !style.isObject())
                    throw new IllegalArgumentException("Некорректное форматирование текста");
                Iterator<Map.Entry<String, JsonNode>> properties = style.properties().iterator();
                if (!properties.hasNext()) throw new IllegalArgumentException("Некорректное форматирование текста");
                while (properties.hasNext()) {
                    Map.Entry<String, JsonNode> property = properties.next();
                    if (!"fontSize".equals(property.getKey()))
                        throw new IllegalArgumentException("Разрешено менять только размер фрагмента текста");
                    double size = property.getValue().asDouble(Double.NaN);
                    if (!Double.isFinite(size) || size < MIN_FONT_SIZE || size > MAX_FONT_SIZE)
                        throw new IllegalArgumentException("Размер текста должен быть от 12 до 144 px");
                }
                if (++entries > MAX_TEXT_STYLE_ENTRIES)
                    throw new IllegalArgumentException("Слишком много форматированных символов");
            }
        }
    }

    private int numericStyleIndex(String value) {
        if (value == null || value.isBlank() || value.length() > 6) return -1;
        for (int i = 0; i < value.length(); i++) if (!Character.isDigit(value.charAt(i))) return -1;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ex) { return -1; }
    }

    private void validateObjectData(WhiteboardObjectType type, JsonNode data) {
        switch (type) {
            case PATH -> validatePath(data);
            case IMAGE -> validateImageTransform(data);
            case TEXT -> validateText(data);
        }
    }

    private void validateImageTransform(JsonNode data) {
        if (data == null || !data.isObject()) throw new IllegalArgumentException("Некорректное положение изображения");
        for (String key : List.of("left", "top", "width", "height", "scaleX", "scaleY", "angle")) {
            double value = data.path(key).asDouble(Double.NaN);
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Некорректное положение изображения");
            if ((key.equals("left") || key.equals("top")) && Math.abs(value) > MAX_COORDINATE)
                throw new IllegalArgumentException("Изображение находится слишком далеко");
        }
        if (data.path("width").asDouble() <= 0 || data.path("width").asDouble() > 4096
                || data.path("height").asDouble() <= 0 || data.path("height").asDouble() > 4096
                || data.path("scaleX").asDouble() <= 0 || data.path("scaleY").asDouble() <= 0
                || data.path("scaleX").asDouble() > 100 || data.path("scaleY").asDouble() > 100)
            throw new IllegalArgumentException("Некорректный размер изображения");
    }

    private void validateNumbers(JsonNode node, int[] count) {
        if (node.isNumber()) {
            double value = node.asDouble();
            if (!Double.isFinite(value) || Math.abs(value) > MAX_COORDINATE)
                throw new IllegalArgumentException("Некорректные координаты штриха");
            count[0]++;
        } else if (node.isArray() || node.isObject()) {
            node.forEach(child -> validateNumbers(child, count));
        }
    }

    private void requireCoordinate(double value) {
        if (!Double.isFinite(value) || Math.abs(value) > MAX_COORDINATE)
            throw new IllegalArgumentException("Некорректные координаты");
    }

    private void requireObjectCapacity(Whiteboard board) {
        if (objects.countByBoardAndDeletedAtIsNull(board) >= MAX_OBJECTS)
            throw new IllegalArgumentException("На доске достигнут лимит объектов");
    }

    private List<UUID> normalizedIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("Выберите объекты");
        List<UUID> result = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (result.isEmpty() || result.size() > MAX_BATCH_OBJECTS)
            throw new IllegalArgumentException("За один раз можно изменить не более 500 объектов");
        return result;
    }

    private void purgeExcessDeleted(Whiteboard board) {
        List<WhiteboardObject> deleted = objects.findTop600ByBoardAndDeletedAtIsNotNullOrderByDeletedAtAsc(board);
        long deletedBytes = images.deletedSizeByBoard(board);
        int removeCount = Math.toIntExact(Math.max(0,
                objects.countByBoardAndDeletedAtIsNotNull(board) - MAX_DELETED_OBJECTS));
        int index = 0;
        while (index < deleted.size() && (index < removeCount || deletedBytes > MAX_DELETED_IMAGE_SIZE)) {
            WhiteboardObject item = deleted.get(index++);
            Optional<WhiteboardImage> image = images.findById(item.getId());
            if (image.isPresent()) {
                deletedBytes -= image.get().getSizeBytes();
                String storedName = image.get().getStoredName();
                images.delete(image.get());
                images.flush();
                deletePhysicalAfterCommit(List.of(storedName));
            }
            objects.delete(item);
        }
        if (index > 0) objects.flush();
    }

    private MutationResult duplicateCreate(Whiteboard board, UUID publicId, WhiteboardObject duplicate,
                                           WhiteboardObjectType expectedType) {
        if (!duplicate.getBoard().getId().equals(board.getId()) || duplicate.getType() != expectedType
                || duplicate.isDeleted()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Идентификатор объекта уже используется");
        }
        return new MutationResult(board.getRevision(), view(duplicate, publicId), false);
    }

    @Scheduled(fixedDelayString = "${app.whiteboard.cleanup-delay-ms:600000}",
            initialDelayString = "${app.whiteboard.cleanup-initial-delay-ms:60000}")
    @Transactional
    public void purgeExpiredDeletedObjects() {
        Instant cutoff = Instant.now().minus(DELETED_RETENTION);
        List<WhiteboardObject> expired = objects.findByDeletedAtBefore(cutoff);
        if (expired.isEmpty()) return;
        List<String> storedNames = new ArrayList<>();
        for (WhiteboardObject item : expired) {
            images.findById(item.getId()).ifPresent(image -> {
                storedNames.add(image.getStoredName());
                images.delete(image);
            });
            objects.delete(item);
        }
        images.flush();
        objects.flush();
        deletePhysicalAfterCommit(storedNames);
    }

    private ProcessedImage processImage(MultipartFile file) {
        String contentType = Optional.ofNullable(file.getContentType()).orElse("");
        if (!contentType.equals("image/jpeg") && !contentType.equals("image/png"))
            throw new IllegalArgumentException("Разрешены только JPEG и PNG");
        try {
            byte[] input = file.getBytes();
            boolean magicMatches = contentType.equals("image/png")
                    ? input.length >= 8 && input[0] == (byte) 0x89 && input[1] == 0x50
                        && input[2] == 0x4e && input[3] == 0x47 && input[4] == 0x0d
                        && input[5] == 0x0a && input[6] == 0x1a && input[7] == 0x0a
                    : input.length >= 3 && input[0] == (byte) 0xff
                        && input[1] == (byte) 0xd8 && input[2] == (byte) 0xff;
            if (!magicMatches)
                throw new IllegalArgumentException("Содержимое файла не соответствует формату JPEG или PNG");
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(input));
            if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0)
                throw new IllegalArgumentException("Файл не является корректным изображением");
            double factor = Math.min(1d, 4096d / Math.max(source.getWidth(), source.getHeight()));
            int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
            int height = Math.max(1, (int) Math.round(source.getHeight() * factor));
            boolean jpeg = contentType.equals("image/jpeg");
            BufferedImage clean = new BufferedImage(width, height, jpeg ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = clean.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            if (jpeg) { graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, width, height); }
            graphics.drawImage(source, 0, 0, width, height, null);
            graphics.dispose();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            String format = jpeg ? "jpg" : "png";
            if (!ImageIO.write(clean, format, output)) throw new IllegalArgumentException("Формат изображения не поддерживается");
            return new ProcessedImage(output.toByteArray(), width, height, jpeg ? ".jpg" : ".png", contentType);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Не удалось прочитать изображение", ex);
        }
    }

    private ObjectView view(WhiteboardObject item, UUID publicId) {
        String imageUrl = item.getType() == WhiteboardObjectType.IMAGE
                ? "/api/boards/" + publicId + "/images/" + item.getId() : null;
        return new ObjectView(item.getId(), item.getType(), parse(item.getData()), item.getZOrder(),
                item.getVersion(), item.getCreatedBy().getDisplayName(), imageUrl);
    }

    private String json(JsonNode node) {
        try { return mapper.writeValueAsString(node); }
        catch (tools.jackson.core.JacksonException ex) { throw new IllegalArgumentException("Некорректные данные объекта", ex); }
    }

    private JsonNode parse(String value) {
        try { return mapper.readTree(value); }
        catch (tools.jackson.core.JacksonException ex) { throw new IllegalStateException("Не удалось прочитать объект доски", ex); }
    }

    private Path safePath(String storedName) {
        Path file = root.resolve(storedName).normalize();
        if (!file.getParent().equals(root)) throw new IllegalStateException("Некорректный путь изображения");
        return file;
    }

    private void deletePhysical(String storedName) {
        if (storedName == null) return;
        try { Files.deleteIfExists(safePath(storedName)); }
        catch (IOException ex) { throw new IllegalStateException("Не удалось удалить изображение доски", ex); }
    }

    private void deletePhysicalAfterCommit(Collection<String> storedNames) {
        List<String> names = storedNames.stream().filter(Objects::nonNull).toList();
        if (names.isEmpty()) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            names.forEach(this::deletePhysical);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { names.forEach(WhiteboardService.this::deletePhysicalQuietly); }
        });
    }

    private void deletePhysicalAfterRollback(String storedName) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) deletePhysicalQuietly(storedName);
            }
        });
    }

    private void deletePhysicalQuietly(String storedName) {
        try { deletePhysical(storedName); }
        catch (RuntimeException ignored) { /* очистка недоступных файлов повторится при запуске */ }
    }

    public record Snapshot(UUID boardId, long revision, Long lessonId, String studentName, String displayName,
                           List<ObjectView> objects) {}
    public record ObjectView(UUID id, WhiteboardObjectType type, JsonNode data, long zOrder, long version,
                             String authorName, String imageUrl) {}
    public record MutationResult(long revision, ObjectView object, boolean changed) {}
    public record BatchMutationResult(long revision, List<ObjectView> objects, boolean changed) {}
    public record VersionedObject(UUID id, long expectedVersion) {}
    public record DeleteResult(long revision, UUID objectId, boolean changed, String storedName) {}
    public record ClearResult(long revision) {}
    public record ImageDownload(WhiteboardImage image, Resource resource) {}
    public record BoardMetadata(UUID boardId, Long lessonId, String displayName, Instant lessonStartAt,
                                int durationMinutes) {}
    private record ProcessedImage(byte[] bytes, int width, int height, String extension, String contentType) {}

    public static class VersionConflictException extends RuntimeException {
        private final ObjectView current;
        public VersionConflictException(ObjectView current) { super("Объект уже изменён другим участником"); this.current = current; }
        public ObjectView getCurrent() { return current; }
    }

    public static class UndoExpiredException extends RuntimeException {
        public UndoExpiredException() { super("Действие уже нельзя отменить"); }
    }
}

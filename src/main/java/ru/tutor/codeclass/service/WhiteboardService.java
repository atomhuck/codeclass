package ru.tutor.codeclass.service;

import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.core.io.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;
import ru.tutor.codeclass.domain.*;
import ru.tutor.codeclass.repository.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class WhiteboardService {
    public static final long MAX_IMAGE_SIZE = 10L * 1024 * 1024;
    public static final int MAX_IMAGES = 20;
    public static final long MAX_BOARD_IMAGE_SIZE = 100L * 1024 * 1024;
    public static final int MAX_OBJECTS = 5_000;
    public static final int MAX_PATH_VALUES = 25_000;
    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final double MAX_COORDINATE = 1_000_000d;

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
                board.getLesson().getStudent().getDisplayName(), views);
    }

    @Transactional
    public MutationResult createPath(User user, UUID publicId, UUID objectId, JsonNode data) {
        Whiteboard board = locked(user, publicId);
        Optional<WhiteboardObject> duplicate = objects.findById(objectId);
        if (duplicate.isPresent()) return new MutationResult(board.getRevision(), view(duplicate.get(), publicId), false);
        if (objects.countByBoard(board) >= MAX_OBJECTS) throw new IllegalArgumentException("На доске достигнут лимит объектов");
        validatePath(data);
        WhiteboardObject item = objects.save(new WhiteboardObject(objectId, board, WhiteboardObjectType.PATH,
                json(data), objects.maxZOrder(board) + 1, user));
        long revision = board.nextRevision();
        return new MutationResult(revision, view(item, publicId), true);
    }

    @Transactional
    public MutationResult updateObject(User user, UUID publicId, UUID objectId, long expectedVersion, JsonNode data) {
        Whiteboard board = locked(user, publicId);
        WhiteboardObject item = objects.findByIdAndBoard(objectId, board)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (item.getType() != WhiteboardObjectType.IMAGE) throw new IllegalArgumentException("Рисунок нельзя перемещать");
        if (item.getVersion() != expectedVersion) throw new VersionConflictException(view(item, publicId));
        validateImageTransform(data);
        item.update(json(data));
        long revision = board.nextRevision();
        return new MutationResult(revision, view(item, publicId), true);
    }

    @Transactional
    public DeleteResult deleteObject(User user, UUID publicId, UUID objectId) {
        Whiteboard board = locked(user, publicId);
        Optional<WhiteboardObject> found = objects.findByIdAndBoard(objectId, board);
        if (found.isEmpty()) return new DeleteResult(board.getRevision(), objectId, false, null);
        WhiteboardObject item = found.get();
        String storedName = images.findById(objectId).map(WhiteboardImage::getStoredName).orElse(null);
        objects.delete(item);
        objects.flush();
        long revision = board.nextRevision();
        deletePhysicalAfterCommit(List.of(storedName));
        return new DeleteResult(revision, objectId, true, storedName);
    }

    @Transactional
    public ClearResult clear(User user, UUID publicId) {
        Whiteboard board = locked(user, publicId);
        if (user.getRole() != Role.TEACHER) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        List<String> storedNames = images.findByBoard(board).stream().map(WhiteboardImage::getStoredName).toList();
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
        if (images.countByBoard(board) >= MAX_IMAGES) throw new IllegalArgumentException("На доске может быть не более 20 изображений");
        if (images.totalSizeByBoard(board) + file.getSize() > MAX_BOARD_IMAGE_SIZE)
            throw new IllegalArgumentException("Изображения на доске превышают общий лимит 100 МБ");
        if (objects.countByBoard(board) >= MAX_OBJECTS) throw new IllegalArgumentException("На доске достигнут лимит объектов");
        requireCoordinate(left); requireCoordinate(top);

        ProcessedImage processed = processImage(file);
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
        Path file = safePath(image.getStoredName());
        if (!Files.isRegularFile(file)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return new ImageDownload(image, new FileSystemResource(file));
    }

    public List<String> storedImagesForLessons(Collection<Lesson> lessons) {
        if (lessons.isEmpty()) return List.of();
        return images.findByLessons(lessons).stream().map(WhiteboardImage::getStoredName).toList();
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
        if (user.getRole() == Role.TEACHER) return;
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

    public record Snapshot(UUID boardId, long revision, Long lessonId, String studentName, List<ObjectView> objects) {}
    public record ObjectView(UUID id, WhiteboardObjectType type, JsonNode data, long zOrder, long version,
                             String authorName, String imageUrl) {}
    public record MutationResult(long revision, ObjectView object, boolean changed) {}
    public record DeleteResult(long revision, UUID objectId, boolean changed, String storedName) {}
    public record ClearResult(long revision) {}
    public record ImageDownload(WhiteboardImage image, Resource resource) {}
    private record ProcessedImage(byte[] bytes, int width, int height, String extension, String contentType) {}

    public static class VersionConflictException extends RuntimeException {
        private final ObjectView current;
        public VersionConflictException(ObjectView current) { super("Объект уже изменён другим участником"); this.current = current; }
        public ObjectView getCurrent() { return current; }
    }
}

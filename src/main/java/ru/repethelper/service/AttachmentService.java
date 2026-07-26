package ru.repethelper.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.repethelper.domain.*;
import ru.repethelper.repository.AttachmentRepository;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Service
public class AttachmentService {
    public static final long MAX_SIZE = 15L * 1024 * 1024;
    public static final int MAX_PER_CATEGORY = 5;
    private static final Set<String> BLOCKED = Set.of("exe", "com", "bat", "cmd", "ps1", "sh", "js", "jar", "msi", "scr", "dll");
    private final AttachmentRepository attachments;
    private final LessonService lessonService;
    private final Path root;
    public AttachmentService(AttachmentRepository attachments, LessonService lessonService, @Value("${app.storage-path}") String path) {
        this.attachments = attachments; this.lessonService = lessonService; this.root = Paths.get(path).toAbsolutePath().normalize();
    }
    @PostConstruct void init() throws IOException { Files.createDirectories(root); }

    @Transactional(readOnly = true)
    public List<Attachment> list(Lesson lesson) { return attachments.findByLessonOrderByCreatedAtAsc(lesson); }

    @Transactional
    public void store(User teacher, Long lessonId, AttachmentCategory category, List<MultipartFile> files) {
        Lesson lesson = lessonService.requireTeacherLesson(teacher, lessonId);
        List<MultipartFile> actual = files == null ? List.of() : files.stream().filter(f -> !f.isEmpty()).toList();
        long current = attachments.countByLessonAndCategory(lesson, category);
        if (current + actual.size() > MAX_PER_CATEGORY) throw new IllegalArgumentException("В одном разделе может быть не более 5 файлов");
        for (MultipartFile file : actual) storeOne(lesson, category, file);
    }

    private void storeOne(Lesson lesson, AttachmentCategory category, MultipartFile file) {
        if (file.getSize() > MAX_SIZE) throw new IllegalArgumentException("Файл превышает лимит 15 МБ");
        String original = Optional.ofNullable(file.getOriginalFilename()).orElse("file");
        original = Paths.get(original).getFileName().toString();
        if (original.length() > 255) original = original.substring(original.length() - 255);
        String ext = original.contains(".") ? original.substring(original.lastIndexOf('.') + 1).toLowerCase() : "";
        if (BLOCKED.contains(ext)) throw new IllegalArgumentException("Этот тип файла запрещён");
        String stored = UUID.randomUUID().toString();
        Path target = root.resolve(stored).normalize();
        if (!target.getParent().equals(root)) throw new IllegalArgumentException("Некорректное имя файла");
        try {
            Files.copy(file.getInputStream(), target);
            String contentType = Optional.ofNullable(file.getContentType()).orElse("application/octet-stream");
            try { attachments.save(new Attachment(lesson, category, original, stored, contentType, file.getSize())); }
            catch (RuntimeException ex) { Files.deleteIfExists(target); throw ex; }
        } catch (IOException ex) { throw new IllegalStateException("Не удалось сохранить файл", ex); }
    }

    @Transactional(readOnly = true)
    public Download load(User user, Long attachmentId) {
        Attachment attachment = attachments.findWithLessonById(attachmentId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        lessonService.requireAccessible(user, attachment.getLesson().getId());
        Path path = root.resolve(attachment.getStoredName()).normalize();
        if (!path.getParent().equals(root) || !Files.isRegularFile(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return new Download(attachment, new FileSystemResource(path));
    }

    @Transactional
    public void delete(User teacher, Long attachmentId) {
        Attachment attachment = attachments.findWithLessonById(attachmentId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        lessonService.requireTeacherLesson(teacher, attachment.getLesson().getId());
        attachments.delete(attachment);
        try { Files.deleteIfExists(root.resolve(attachment.getStoredName()).normalize()); }
        catch (IOException ex) { throw new IllegalStateException("Не удалось удалить файл", ex); }
    }
    public record Download(Attachment attachment, Resource resource) {}
}

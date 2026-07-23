package ru.tutor.codeclass.web;

import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.tutor.codeclass.domain.*;
import ru.tutor.codeclass.service.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Controller
public class AttachmentController {
    private final AccountService accounts;
    private final AttachmentService attachments;
    public AttachmentController(AccountService accounts, AttachmentService attachments) { this.accounts = accounts; this.attachments = attachments; }

    @PostMapping("/teacher/lessons/{lessonId}/attachments")
    String upload(Authentication auth, @PathVariable Long lessonId, @RequestParam AttachmentCategory category,
                  @RequestParam("files") List<MultipartFile> files, RedirectAttributes flash) {
        try { attachments.store(current(auth), lessonId, category, files); flash.addFlashAttribute("success", "Файлы добавлены"); }
        catch (IllegalArgumentException | IllegalStateException ex) { flash.addFlashAttribute("error", ex.getMessage()); }
        return "redirect:/lessons/" + lessonId;
    }

    @GetMapping("/attachments/{id}")
    ResponseEntity<org.springframework.core.io.Resource> download(Authentication auth, @PathVariable Long id) {
        var download = attachments.load(current(auth), id);
        ContentDisposition disposition = ContentDisposition.attachment().filename(download.attachment().getOriginalName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(download.attachment().getContentType()))
                .contentLength(download.attachment().getSizeBytes()).header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff").body(download.resource());
    }

    @PostMapping("/teacher/attachments/{id}/delete")
    String delete(Authentication auth, @PathVariable Long id, @RequestParam Long lessonId, RedirectAttributes flash) {
        attachments.delete(current(auth), id); flash.addFlashAttribute("success", "Файл удалён"); return "redirect:/lessons/" + lessonId;
    }
    private User current(Authentication auth) { return accounts.requireByUsername(auth.getName()); }
}

package ru.tutor.codeclass.web;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ru.tutor.codeclass.domain.*;
import ru.tutor.codeclass.service.*;
import ru.tutor.codeclass.web.form.*;
import java.time.LocalDateTime;

@Controller
public class LessonController {
    private final AccountService accounts;
    private final LessonService lessons;
    private final AttachmentService attachments;
    private final WhiteboardService whiteboards;
    public LessonController(AccountService accounts, LessonService lessons, AttachmentService attachments,
                            WhiteboardService whiteboards) {
        this.accounts = accounts; this.lessons = lessons; this.attachments = attachments; this.whiteboards = whiteboards;
    }
    @GetMapping("/lessons/{id}")
    String details(Authentication auth, @PathVariable Long id, Model model) {
        User user = accounts.requireByUsername(auth.getName());
        Lesson lesson = lessons.requireAccessible(user, id);
        Whiteboard board = whiteboards.getOrCreate(user, lesson);
        var all = attachments.list(lesson);
        LessonMaterialsForm materials = new LessonMaterialsForm();
        materials.setHomeworkText(lesson.getHomeworkText()); materials.setLessonNotesText(lesson.getLessonNotesText());
        LessonForm schedule = new LessonForm();
        schedule.setStudentId(lesson.getStudent().getId()); schedule.setStartAt(LocalDateTime.ofInstant(lesson.getStartAt(), lessons.zone()));
        schedule.setDurationMinutes(lesson.getDurationMinutes());
        model.addAttribute("user", user);
        model.addAttribute("lesson", lesson);
        model.addAttribute("board", board);
        model.addAttribute("past", lessons.isPast(lesson));
        model.addAttribute("materialsForm", materials);
        model.addAttribute("lessonForm", schedule);
        model.addAttribute("homeworkFiles", all.stream().filter(a -> a.getCategory() == AttachmentCategory.HOMEWORK).toList());
        model.addAttribute("notesFiles", all.stream().filter(a -> a.getCategory() == AttachmentCategory.LESSON_NOTES).toList());
        return "lesson";
    }
}

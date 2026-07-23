package ru.tutor.codeclass.web;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ru.tutor.codeclass.domain.*;
import ru.tutor.codeclass.service.*;
import java.util.UUID;

@Controller
public class WhiteboardPageController {
    private final AccountService accounts;
    private final LessonService lessons;
    private final WhiteboardService boards;

    public WhiteboardPageController(AccountService accounts, LessonService lessons, WhiteboardService boards) {
        this.accounts = accounts; this.lessons = lessons; this.boards = boards;
    }

    @GetMapping("/lessons/{id}/board")
    String fromLesson(Authentication auth, @PathVariable Long id) {
        User user = accounts.requireByUsername(auth.getName());
        Lesson lesson = lessons.requireAccessible(user, id);
        Whiteboard board = boards.getOrCreate(user, lesson);
        return "redirect:/boards/" + board.getPublicId();
    }

    @GetMapping("/boards/{publicId}")
    String board(Authentication auth, @PathVariable UUID publicId, Model model) {
        User user = accounts.requireByUsername(auth.getName());
        Whiteboard board = boards.requireAccessible(user, publicId);
        model.addAttribute("user", user);
        model.addAttribute("board", board);
        model.addAttribute("lesson", board.getLesson());
        return "whiteboard";
    }
}

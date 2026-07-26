package ru.repethelper.web;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.repethelper.domain.User;
import ru.repethelper.service.AccountService;
import ru.repethelper.service.StudentRemovalService;

@Controller
@RequestMapping("/teacher/students")
public class StudentRemovalController {
    private final AccountService accounts;
    private final StudentRemovalService removals;

    public StudentRemovalController(AccountService accounts, StudentRemovalService removals) {
        this.accounts = accounts; this.removals = removals;
    }

    @GetMapping("/{studentId}/remove")
    String confirm(Authentication auth, @PathVariable Long studentId, Model model) {
        User teacher = current(auth);
        model.addAttribute("user", teacher);
        model.addAttribute("preview", removals.preview(teacher, studentId));
        return "teacher/student-remove";
    }

    @PostMapping("/{studentId}/remove")
    String remove(Authentication auth, @PathVariable Long studentId, RedirectAttributes flash) {
        var summary = removals.remove(current(auth), studentId);
        flash.addFlashAttribute("success", "Ученик удалён: занятий — " + summary.lessonCount()
                + ", файлов — " + summary.attachmentCount() + ", досок — " + summary.boardCount());
        return "redirect:/teacher/students";
    }

    private User current(Authentication auth) { return accounts.requireByUsername(auth.getName()); }
}

package ru.repethelper.web;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.repethelper.domain.User;
import ru.repethelper.service.AccountService;
import ru.repethelper.service.TeacherStudentBoardService;

@Controller
@RequestMapping("/teacher")
public class TeacherStudentBoardController {
    private final AccountService accounts;
    private final TeacherStudentBoardService boardHistory;

    public TeacherStudentBoardController(AccountService accounts, TeacherStudentBoardService boardHistory) {
        this.accounts = accounts;
        this.boardHistory = boardHistory;
    }

    @GetMapping("/students/{studentId}/boards")
    String boards(Authentication auth, @PathVariable Long studentId,
                  @RequestParam(defaultValue = "0") int page, Model model) {
        User teacher = accounts.requireByUsername(auth.getName());
        var history = boardHistory.get(teacher, studentId, page);
        model.addAttribute("user", teacher);
        model.addAttribute("student", history.student());
        model.addAttribute("boards", history.boards());
        return "teacher/student-boards";
    }
}

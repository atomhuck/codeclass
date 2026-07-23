package ru.tutor.codeclass.web;

import jakarta.validation.Valid;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import ru.tutor.codeclass.service.AccountService;
import ru.tutor.codeclass.web.form.RegistrationForm;

@Controller
public class AuthController {
    private final AccountService accounts;
    public AuthController(AccountService accounts) { this.accounts = accounts; }

    @GetMapping("/login") String login() { return "login"; }
    @GetMapping("/register") String register(Model model) {
        model.addAttribute("form", new RegistrationForm()); return "register";
    }
    @PostMapping("/register")
    String register(@Valid @ModelAttribute("form") RegistrationForm form, BindingResult errors, Model model,
                    jakarta.servlet.http.HttpServletRequest request) {
        if (errors.hasErrors()) return "register";
        try {
            var user = accounts.registerStudent(form.getDisplayName(), form.getUsername(), form.getPassword());
            var details = accounts.loadUserByUsername(user.getUsername());
            var auth = new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);
            request.getSession(true).setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, SecurityContextHolder.getContext());
            return "redirect:/student?welcome";
        } catch (IllegalArgumentException ex) {
            model.addAttribute("registrationError", ex.getMessage()); return "register";
        }
    }
}

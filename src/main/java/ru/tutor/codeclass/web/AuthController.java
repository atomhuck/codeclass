package ru.tutor.codeclass.web;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.mail.MailException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.tutor.codeclass.domain.*;
import ru.tutor.codeclass.service.*;
import ru.tutor.codeclass.web.form.RegistrationForm;

@Controller
public class AuthController {
    private static final String RESET_IDENTIFIER_SESSION = "passwordResetIdentifier";
    private final AccountService accounts;
    private final AccountTokenService tokens;
    private final NotificationMailService mail;
    private final LoginAttemptService attempts;

    public AuthController(AccountService accounts, AccountTokenService tokens,
                          NotificationMailService mail, LoginAttemptService attempts) {
        this.accounts = accounts; this.tokens = tokens; this.mail = mail; this.attempts = attempts;
    }

    @GetMapping("/login") String login() { return "login"; }
    @GetMapping("/register") String register(Model model) {
        model.addAttribute("form", new RegistrationForm()); return "register";
    }

    @PostMapping("/register")
    String register(@Valid @ModelAttribute("form") RegistrationForm form, BindingResult errors, Model model,
                    HttpServletRequest request) {
        if (errors.hasErrors()) return "register";
        try {
            User user = accounts.register(form.getDisplayName(), form.getUsername(), form.getEmail(),
                    form.getPassword(), form.getRole(), true);
            authenticate(user, request);
            safeSendVerification(user);
            return "redirect:/verify-email/pending?welcome";
        } catch (IllegalArgumentException ex) {
            model.addAttribute("registrationError", ex.getMessage()); return "register";
        }
    }

    @GetMapping("/account/consent")
    String consent(Authentication auth, Model model) {
        User user = current(auth);
        model.addAttribute("user", user);
        model.addAttribute("email", user.getEmail());
        return "account-consent";
    }

    @PostMapping("/account/consent")
    String consent(Authentication auth, @RequestParam String email,
                   @RequestParam(defaultValue = "false") boolean termsAccepted,
                   @RequestParam(defaultValue = "false") boolean personalDataAccepted,
                   RedirectAttributes flash) {
        if (!termsAccepted || !personalDataAccepted) {
            flash.addFlashAttribute("error", "Необходимо принять оба документа");
            return "redirect:/account/consent";
        }
        try {
            User user = current(auth);
            User updated = accounts.completeLegacyProfile(user, email);
            if (updated.isEmailVerified()) return homeFor(updated);
            safeSendVerification(updated);
            return "redirect:/verify-email/pending";
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
            return "redirect:/account/consent";
        }
    }

    @GetMapping("/verify-email")
    String legacyVerificationLink(RedirectAttributes flash) {
        flash.addFlashAttribute("error", "Подтверждение по ссылке больше не используется. Введите код из нового письма.");
        return "redirect:/verify-email/pending";
    }

    @GetMapping("/verify-email/pending")
    String verificationPending(Authentication auth, Model model) {
        User user = current(auth);
        if (user.isEmailVerified()) return homeFor(user);
        model.addAttribute("user", user);
        return "verify-email-pending";
    }

    @PostMapping("/verify-email")
    String verify(Authentication auth, @RequestParam String code, RedirectAttributes flash) {
        User user = current(auth);
        if (tokens.verifyEmail(user, code)) return homeFor(user);
        flash.addFlashAttribute("error",
                "Неверный или просроченный код. После пяти ошибок запросите новый код.");
        return "redirect:/verify-email/pending";
    }

    @PostMapping("/verify-email/resend")
    String resend(Authentication auth, HttpServletRequest request, RedirectAttributes flash) {
        User user = current(auth);
        if (!attempts.verificationResendAllowed(user.getUsername(), request.getRemoteAddr())) {
            flash.addFlashAttribute("error", "Слишком много запросов. Попробуйте получить новый код позже.");
            return "redirect:/verify-email/pending";
        }
        try {
            sendVerification(user);
            flash.addFlashAttribute("success", "Новый шестизначный код отправлен");
        } catch (MailException ex) {
            flash.addFlashAttribute("error", "Не удалось отправить письмо. Попробуйте позже");
        }
        return "redirect:/verify-email/pending";
    }

    @GetMapping("/forgot-password") String forgot() { return "forgot-password"; }

    @PostMapping("/forgot-password")
    String forgot(@RequestParam String identifier, HttpServletRequest request, RedirectAttributes flash) {
        String normalizedIdentifier = identifier == null ? "" : identifier.trim();
        request.getSession(true).setAttribute(RESET_IDENTIFIER_SESSION, normalizedIdentifier);
        if (attempts.passwordResetAllowed(identifier, request.getRemoteAddr())) {
            tokens.createPasswordReset(identifier).ifPresent(delivery -> {
                try { mail.sendPasswordReset(delivery.email(), delivery.code()); }
                catch (MailException ignored) { /* одинаковый ответ не раскрывает наличие аккаунта */ }
            });
        }
        flash.addFlashAttribute("success",
                "Если аккаунт существует и email подтверждён, мы отправили шестизначный код");
        return "redirect:/reset-password?requested";
    }

    @GetMapping("/reset-password")
    String reset(HttpSession session, Model model) {
        Object identifier = session.getAttribute(RESET_IDENTIFIER_SESSION);
        model.addAttribute("identifier", identifier == null ? "" : identifier);
        return "reset-password";
    }

    @PostMapping("/reset-password")
    String reset(@RequestParam String identifier, @RequestParam String code,
                 @RequestParam String password, @RequestParam String passwordConfirmation,
                 HttpSession session, RedirectAttributes flash) {
        session.setAttribute(RESET_IDENTIFIER_SESSION, identifier == null ? "" : identifier.trim());
        if (!password.equals(passwordConfirmation)) {
            flash.addFlashAttribute("error", "Пароли не совпадают");
            return "redirect:/reset-password";
        }
        try {
            if (!tokens.resetPassword(identifier, code, password)) {
                flash.addFlashAttribute("error",
                        "Неверный или просроченный код. После пяти ошибок запросите новый код.");
                return "redirect:/reset-password";
            }
            session.removeAttribute(RESET_IDENTIFIER_SESSION);
            return "redirect:/login?reset";
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
            return "redirect:/reset-password";
        }
    }

    private void authenticate(User user, HttpServletRequest request) {
        var details = accounts.loadUserByUsername(user.getUsername());
        var auth = new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
        request.getSession(true).setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                SecurityContextHolder.getContext());
    }

    private void sendVerification(User user) {
        mail.sendVerification(user.getEmail(), tokens.createVerification(user));
    }

    private void safeSendVerification(User user) {
        try { sendVerification(user); }
        catch (MailException ignored) { /* повторная отправка доступна на следующем экране */ }
    }

    private User current(Authentication auth) { return accounts.requireByUsername(auth.getName()); }

    private String homeFor(User user) {
        return user.getRole() == Role.TEACHER ? "redirect:/teacher" : "redirect:/student";
    }
}

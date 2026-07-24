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
            accounts.completeLegacyProfile(user, email);
            safeSendVerification(user);
            return "redirect:/verify-email/pending";
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
            return "redirect:/account/consent";
        }
    }

    @GetMapping("/verify-email")
    String verify(@RequestParam(required = false) String token) {
        return tokens.verifyEmail(token) ? "redirect:/login?verified" : "redirect:/login?invalidToken";
    }

    @GetMapping("/verify-email/pending")
    String verificationPending(Authentication auth, Model model) {
        model.addAttribute("user", current(auth));
        return "verify-email-pending";
    }

    @PostMapping("/verify-email/resend")
    String resend(Authentication auth, RedirectAttributes flash) {
        try {
            sendVerification(current(auth));
            flash.addFlashAttribute("success", "Новое письмо отправлено");
        } catch (MailException ex) {
            flash.addFlashAttribute("error", "Не удалось отправить письмо. Попробуйте позже");
        }
        return "redirect:/verify-email/pending";
    }

    @GetMapping("/forgot-password") String forgot() { return "forgot-password"; }

    @PostMapping("/forgot-password")
    String forgot(@RequestParam String identifier, HttpServletRequest request, RedirectAttributes flash) {
        if (attempts.passwordResetAllowed(identifier, request.getRemoteAddr())) {
            tokens.createPasswordReset(identifier).ifPresent(delivery -> {
                try { mail.sendPasswordReset(delivery.email(), delivery.token()); }
                catch (MailException ignored) { /* одинаковый ответ не раскрывает наличие аккаунта */ }
            });
        }
        flash.addFlashAttribute("success",
                "Если аккаунт существует и email подтверждён, мы отправили ссылку для сброса");
        return "redirect:/forgot-password";
    }

    @GetMapping("/reset-password")
    String reset(@RequestParam(required = false) String token, Model model) {
        model.addAttribute("token", token); return "reset-password";
    }

    @PostMapping("/reset-password")
    String reset(@RequestParam String token, @RequestParam String password,
                 @RequestParam String passwordConfirmation, RedirectAttributes flash) {
        if (!password.equals(passwordConfirmation)) {
            flash.addFlashAttribute("error", "Пароли не совпадают");
            return "redirect:/reset-password?token=" + token;
        }
        try {
            if (!tokens.resetPassword(token, password)) {
                flash.addFlashAttribute("error", "Ссылка недействительна или устарела");
                return "redirect:/reset-password?token=" + token;
            }
            return "redirect:/login?reset";
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
            return "redirect:/reset-password?token=" + token;
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
}

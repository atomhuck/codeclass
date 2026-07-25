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
import ru.tutor.codeclass.web.form.VkRegistrationForm;

@Controller
public class AuthController {
    private static final String RESET_IDENTIFIER_SESSION = "passwordResetIdentifier";
    private final AccountService accounts;
    private final AccountTokenService tokens;
    private final NotificationMailService mail;
    private final LoginAttemptService attempts;
    private final VkAuthService vk;

    public AuthController(AccountService accounts, AccountTokenService tokens,
                          NotificationMailService mail, LoginAttemptService attempts, VkAuthService vk) {
        this.accounts = accounts; this.tokens = tokens; this.mail = mail; this.attempts = attempts; this.vk = vk;
    }

    @GetMapping("/login") String login(Model model) { model.addAttribute("vkEnabled", vk.isEnabled()); return "login"; }
    @GetMapping("/register") String register(Model model) {
        model.addAttribute("form", new RegistrationForm()); model.addAttribute("vkEnabled", vk.isEnabled()); return "register";
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

    @GetMapping("/oauth2/authorization/vk")
    String beginVk(HttpServletRequest request, RedirectAttributes flash) {
        try { return "redirect:" + vk.begin(request.getSession(true), VkAuthService.Purpose.LOGIN, null); }
        catch (IllegalArgumentException | IllegalStateException ex) { flash.addFlashAttribute("error", ex.getMessage()); return "redirect:/login"; }
    }

    @GetMapping("/login/oauth2/code/vk")
    String vkCallback(@RequestParam(required = false) String state, @RequestParam(required = false) String code,
                      @RequestParam(name = "device_id", required = false) String deviceId,
                      @RequestParam(required = false) String error, HttpServletRequest request,
                      RedirectAttributes flash) {
        if (error != null) { flash.addFlashAttribute("error", "Вход через VK отменён или не завершён"); return "redirect:/login"; }
        try {
            VkAuthService.VkProfile profile = vk.finish(request.getSession(true), state, code, deviceId);
            if (profile.purpose() == VkAuthService.Purpose.LINK) {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth == null || !auth.isAuthenticated() || !current(auth).getId().equals(profile.linkUserId()))
                    throw new IllegalArgumentException("Сеанс привязки VK больше не действителен");
                vk.link(current(auth), profile); vk.clearPending(request.getSession());
                flash.addFlashAttribute("success", "VK успешно привязан"); return "redirect:/account/security";
            }
            var existing = vk.findIdentity(profile.subject());
            if (existing.isPresent()) {
                vk.recordLogin(existing.get()); authenticate(existing.get().getUser(), request);
                attempts.loginSucceeded(existing.get().getUser().getUsername()); vk.clearPending(request.getSession());
                return homeFor(existing.get().getUser());
            }
            if (profile.email() != null && accounts.requireByIdentifierOrNull(profile.email()) != null)
                return "redirect:/auth/vk/account-exists";
            return "redirect:/auth/vk/onboarding";
        } catch (IllegalArgumentException ex) { flash.addFlashAttribute("error", ex.getMessage()); return "redirect:/login"; }
    }

    @GetMapping("/auth/vk/onboarding")
    String vkOnboarding(HttpSession session, Model model, RedirectAttributes flash) {
        VkAuthService.VkProfile profile = vk.pending(session);
        if (profile == null) { flash.addFlashAttribute("error", "Начните вход через VK заново"); return "redirect:/login"; }
        VkRegistrationForm form = new VkRegistrationForm(); form.setDisplayName(profile.displayName()); form.setEmail(profile.email());
        String safeSubject = profile.subject().replaceAll("[^A-Za-z0-9]", "");
        form.setUsername("vk_" + (safeSubject.isBlank() ? "account" : safeSubject.substring(0, Math.min(8, safeSubject.length()))));
        model.addAttribute("form", form); model.addAttribute("emailFromVk", profile.email() != null); return "vk-onboarding";
    }

    @PostMapping("/auth/vk/onboarding")
    String completeVkOnboarding(@Valid @ModelAttribute("form") VkRegistrationForm form, BindingResult errors,
                                HttpSession session, HttpServletRequest request, Model model) {
        VkAuthService.VkProfile profile = vk.pending(session);
        if (profile == null) return "redirect:/login";
        if (errors.hasErrors()) { model.addAttribute("emailFromVk", profile.email() != null); return "vk-onboarding"; }
        try {
            User user = vk.createAccount(profile, form.getDisplayName(), form.getUsername(), form.getEmail(), form.getRole());
            vk.clearPending(session); authenticate(user, request);
            if (!user.isEmailVerified()) { safeSendVerification(user); return "redirect:/verify-email/pending?welcome"; }
            return homeFor(user);
        } catch (IllegalArgumentException ex) { model.addAttribute("registrationError", ex.getMessage()); model.addAttribute("emailFromVk", true); return "vk-onboarding"; }
    }

    @GetMapping("/auth/vk/account-exists")
    String vkAccountExists(HttpSession session, Model model, RedirectAttributes flash) {
        if (vk.pending(session) == null) { flash.addFlashAttribute("error", "Начните вход через VK заново"); return "redirect:/login"; }
        return "vk-account-exists";
    }

    @PostMapping("/auth/vk/account-exists")
    String linkVkToExisting(@RequestParam String identifier, @RequestParam String password, HttpServletRequest request,
                            HttpSession session, RedirectAttributes flash) {
        VkAuthService.VkProfile profile = vk.pending(session);
        if (profile == null) return "redirect:/login";
        if (!attempts.loginAllowed(identifier, request.getRemoteAddr())) { flash.addFlashAttribute("error", "Слишком много попыток. Повторите позже."); return "redirect:/auth/vk/account-exists"; }
        try {
            User user = accounts.requireByIdentifier(identifier);
            if (!accounts.matchesPassword(user, password)) throw new IllegalArgumentException("Неверные данные");
            vk.link(user, profile); if (profile.email() != null && user.getEmail().equalsIgnoreCase(profile.email()) && !user.isEmailVerified()) user.verifyEmail();
            attempts.loginSucceeded(user.getUsername()); vk.clearPending(session); authenticate(user, request); return homeFor(user);
        } catch (RuntimeException ex) { attempts.loginFailed(identifier, request.getRemoteAddr()); flash.addFlashAttribute("error", "Не удалось подтвердить аккаунт"); return "redirect:/auth/vk/account-exists"; }
    }

    @GetMapping("/account/security")
    String security(Authentication auth, Model model) {
        User user = current(auth); model.addAttribute("user", user); model.addAttribute("vkIdentity", vk.findVkIdentity(user).orElse(null)); return "account-security";
    }

    @GetMapping("/account/security/vk/link")
    String beginVkLink(Authentication auth, HttpServletRequest request, RedirectAttributes flash) {
        try { return "redirect:" + vk.begin(request.getSession(true), VkAuthService.Purpose.LINK, current(auth).getId()); }
        catch (IllegalArgumentException | IllegalStateException ex) { flash.addFlashAttribute("error", ex.getMessage()); return "redirect:/account/security"; }
    }

    @PostMapping("/account/security/vk/unlink")
    String unlinkVk(Authentication auth, @RequestParam String password, HttpSession session, RedirectAttributes flash) {
        User user = current(auth);
        if (!accounts.matchesPassword(user, password)) { flash.addFlashAttribute("error", "Неверный пароль"); return "redirect:/account/security"; }
        try { vk.unlink(user); accounts.invalidateSessions(user); session.invalidate(); SecurityContextHolder.clearContext(); return "redirect:/login?securityChanged"; }
        catch (IllegalArgumentException ex) { flash.addFlashAttribute("error", ex.getMessage()); }
        return "redirect:/account/security";
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
        request.changeSessionId();
        var details = accounts.principalFor(user);
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

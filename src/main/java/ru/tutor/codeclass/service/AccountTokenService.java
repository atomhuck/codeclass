package ru.tutor.codeclass.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.tutor.codeclass.domain.*;
import ru.tutor.codeclass.repository.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class AccountTokenService {
    private final EmailVerificationTokenRepository verificationTokens;
    private final PasswordResetTokenRepository resetTokens;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final SecureRandom random = new SecureRandom();

    public AccountTokenService(EmailVerificationTokenRepository verificationTokens,
            PasswordResetTokenRepository resetTokens, UserRepository users, PasswordEncoder encoder) {
        this.verificationTokens = verificationTokens; this.resetTokens = resetTokens;
        this.users = users; this.encoder = encoder;
    }

    @Transactional
    public String createVerification(User user) {
        if (user.getEmail() == null) throw new IllegalArgumentException("Сначала укажите email");
        verificationTokens.deleteByUserId(user.getId());
        String raw = randomToken();
        verificationTokens.save(new EmailVerificationToken(user, digest(raw),
                Instant.now().plus(24, ChronoUnit.HOURS)));
        return raw;
    }

    @Transactional
    public boolean verifyEmail(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return false;
        EmailVerificationToken token = verificationTokens.findByTokenHash(digest(rawToken)).orElse(null);
        if (token == null || !token.isUsable(Instant.now())) return false;
        token.use();
        token.getUser().verifyEmail();
        return true;
    }

    @Transactional
    public Optional<ResetDelivery> createPasswordReset(String identifier) {
        String normalized = identifier == null ? "" : identifier.trim().toLowerCase(Locale.ROOT);
        Optional<User> found = users.findByUsernameIgnoreCaseOrEmailIgnoreCase(normalized, normalized);
        if (found.isEmpty() || !found.get().isEmailVerified()) return Optional.empty();
        User user = found.get();
        resetTokens.deleteByUserId(user.getId());
        String raw = randomToken();
        resetTokens.save(new PasswordResetToken(user, digest(raw), Instant.now().plus(30, ChronoUnit.MINUTES)));
        return Optional.of(new ResetDelivery(user.getEmail(), raw));
    }

    @Transactional
    public boolean resetPassword(String rawToken, String password) {
        if (rawToken == null || rawToken.isBlank()) return false;
        PasswordResetToken token = resetTokens.findByTokenHash(digest(rawToken)).orElse(null);
        if (token == null || !token.isUsable(Instant.now())) return false;
        if (password == null || password.length() < 10
                || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("Пароль должен содержать не менее 10 символов и не более 72 байт");
        token.use();
        token.getUser().changePassword(encoder.encode(password));
        return true;
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String digest(String token) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 недоступен", ex);
        }
    }

    public record ResetDelivery(String email, String token) {}
}

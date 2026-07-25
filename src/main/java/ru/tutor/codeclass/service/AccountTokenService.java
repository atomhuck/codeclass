package ru.tutor.codeclass.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.tutor.codeclass.domain.*;
import ru.tutor.codeclass.repository.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
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
        User managed = users.findById(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден"));
        if (managed.getEmail() == null) throw new IllegalArgumentException("Сначала укажите email");
        String previousHash = verificationTokens.findFirstByUserIdOrderByCreatedAtDesc(managed.getId())
                .map(EmailVerificationToken::getTokenHash).orElse(null);
        String code = newCode(previousHash);
        verificationTokens.deleteByUserId(managed.getId());
        verificationTokens.save(new EmailVerificationToken(managed, encoder.encode(code),
                Instant.now().plus(15, ChronoUnit.MINUTES)));
        return code;
    }

    @Transactional
    public boolean verifyEmail(User user, String code) {
        if (!validCode(code)) return false;
        EmailVerificationToken token = verificationTokens
                .findFirstByUserIdOrderByCreatedAtDesc(user.getId()).orElse(null);
        if (token == null || !token.isUsable(Instant.now())) return false;
        if (!encoder.matches(code.trim(), token.getTokenHash())) {
            token.recordFailure();
            return false;
        }
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
        String previousHash = resetTokens.findFirstByUserIdOrderByCreatedAtDesc(user.getId())
                .map(PasswordResetToken::getTokenHash).orElse(null);
        String code = newCode(previousHash);
        resetTokens.deleteByUserId(user.getId());
        resetTokens.save(new PasswordResetToken(user, encoder.encode(code),
                Instant.now().plus(15, ChronoUnit.MINUTES)));
        return Optional.of(new ResetDelivery(user.getEmail(), code));
    }

    @Transactional
    public boolean resetPassword(String identifier, String code, String password) {
        if (!validCode(code)) return false;
        String normalized = identifier == null ? "" : identifier.trim().toLowerCase(Locale.ROOT);
        User user = users.findByUsernameIgnoreCaseOrEmailIgnoreCase(normalized, normalized).orElse(null);
        if (user == null) return false;
        PasswordResetToken token = resetTokens
                .findFirstByUserIdOrderByCreatedAtDesc(user.getId()).orElse(null);
        if (token == null || !token.isUsable(Instant.now())) return false;
        if (!encoder.matches(code.trim(), token.getTokenHash())) {
            token.recordFailure();
            return false;
        }
        if (password == null || password.length() < 10
                || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("Пароль должен содержать не менее 10 символов и не более 72 байт");
        token.use();
        token.getUser().changePassword(encoder.encode(password));
        return true;
    }

    private String newCode(String previousHash) {
        for (int attempt = 0; attempt < 20; attempt++) {
            String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
            if (previousHash == null || !encoder.matches(code, previousHash)) return code;
        }
        throw new IllegalStateException("Не удалось создать одноразовый код");
    }

    private boolean validCode(String code) {
        return code != null && code.trim().matches("\\d{6}");
    }

    public record ResetDelivery(String email, String code) {}
}

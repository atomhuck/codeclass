package ru.tutor.codeclass.service;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LoginAttemptService {
    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);
    private final AccountService accounts;
    private final Map<String, Deque<Instant>> loginByIp = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> resetByIp = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> resetByAccount = new ConcurrentHashMap<>();

    public LoginAttemptService(AccountService accounts) { this.accounts = accounts; }

    public boolean loginAllowed(String identifier, String ip) {
        boolean allowed = count(loginByIp, ip, Duration.ofMinutes(15)) < 20
                && !accounts.isAccountLocked(identifier, Instant.now());
        if (!allowed) log.warn("security.login_blocked account={} ip={}", identifierHash(identifier), safe(ip));
        return allowed;
    }

    public void loginFailed(String identifier, String ip) {
        add(loginByIp, ip, Duration.ofMinutes(15));
        accounts.recordLoginFailure(identifier, Instant.now());
        log.warn("security.login_failed account={} ip={}", identifierHash(identifier), safe(ip));
    }

    public void loginSucceeded(String username) { accounts.clearLoginFailures(username); }

    public boolean passwordResetAllowed(String identifier, String ip) {
        String account = identifier == null ? "" : identifier.trim().toLowerCase(Locale.ROOT);
        if (count(resetByIp, ip, Duration.ofHours(1)) >= 10
                || count(resetByAccount, account, Duration.ofHours(1)) >= 5) return false;
        add(resetByIp, ip, Duration.ofHours(1));
        add(resetByAccount, account, Duration.ofHours(1));
        return true;
    }

    private int count(Map<String, Deque<Instant>> buckets, String key, Duration window) {
        Deque<Instant> events = buckets.computeIfAbsent(safe(key), ignored -> new ArrayDeque<>());
        synchronized (events) { prune(events, window); return events.size(); }
    }

    private void add(Map<String, Deque<Instant>> buckets, String key, Duration window) {
        Deque<Instant> events = buckets.computeIfAbsent(safe(key), ignored -> new ArrayDeque<>());
        synchronized (events) { prune(events, window); events.addLast(Instant.now()); }
    }

    private void prune(Deque<Instant> events, Duration window) {
        Instant cutoff = Instant.now().minus(window);
        while (!events.isEmpty() && events.getFirst().isBefore(cutoff)) events.removeFirst();
    }

    private String safe(String value) { return value == null || value.isBlank() ? "unknown" : value; }

    private String identifierHash(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(safe(value).toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 6);
        } catch (NoSuchAlgorithmException ex) {
            return "unavailable";
        }
    }
}

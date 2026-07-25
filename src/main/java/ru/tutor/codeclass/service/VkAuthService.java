package ru.tutor.codeclass.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import ru.tutor.codeclass.domain.ExternalIdentity;
import ru.tutor.codeclass.domain.Role;
import ru.tutor.codeclass.domain.User;
import ru.tutor.codeclass.repository.ExternalIdentityRepository;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Server-side VK ID OAuth helper. Authorization state and PKCE verifier never leave the session. */
@Service
public class VkAuthService {
    public static final String PROVIDER = "VK";
    public static final String STATE_SESSION = "vkOAuthState";
    public static final String PROFILE_SESSION = "vkOAuthProfile";
    private final ExternalIdentityRepository identities;
    private final AccountService accounts;
    private final ObjectMapper mapper;
    private final RestClient client;
    private final SecureRandom random = new SecureRandom();
    private final boolean enabled;
    private final String clientId, clientSecret, redirectUri, authorizeUrl, tokenUrl, userInfoUrl;

    public VkAuthService(ExternalIdentityRepository identities, AccountService accounts, ObjectMapper mapper,
                         @Value("${app.vk.enabled:false}") boolean enabled,
                         @Value("${app.vk.client-id:}") String clientId,
                         @Value("${app.vk.client-secret:}") String clientSecret,
                         @Value("${app.vk.redirect-uri:}") String redirectUri,
                         @Value("${app.vk.authorize-url:https://id.vk.com/authorize}") String authorizeUrl,
                         @Value("${app.vk.token-url:https://id.vk.com/oauth2/auth}") String tokenUrl,
                         @Value("${app.vk.user-info-url:https://id.vk.com/oauth2/user_info}") String userInfoUrl) {
        this.identities = identities; this.accounts = accounts; this.mapper = mapper;
        this.client = RestClient.builder().build(); this.enabled = enabled; this.clientId = clientId;
        this.clientSecret = clientSecret; this.redirectUri = redirectUri; this.authorizeUrl = authorizeUrl;
        this.tokenUrl = tokenUrl; this.userInfoUrl = userInfoUrl;
    }

    public boolean isEnabled() { return enabled && !clientId.isBlank() && !redirectUri.isBlank(); }

    public String begin(HttpSession session, Purpose purpose, Long userId) {
        if (!isEnabled()) throw new IllegalStateException("Вход через VK пока не настроен");
        String state = randomUrl(32), verifier = randomUrl(48);
        session.setAttribute(STATE_SESSION, new OAuthState(state, verifier, purpose, userId, Instant.now().plusSeconds(600)));
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code"); params.put("client_id", clientId); params.put("redirect_uri", redirectUri);
        params.put("state", state); params.put("code_challenge", sha256(verifier));
        params.put("code_challenge_method", "S256"); params.put("scope", "email");
        return authorizeUrl + "?" + params.entrySet().stream()
                .map(e -> java.net.URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + java.net.URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    public VkProfile finish(HttpSession session, String state, String code, String deviceId) {
        Object stored = session.getAttribute(STATE_SESSION);
        if (!(stored instanceof OAuthState oauth) || state == null || !MessageDigest.isEqual(
                oauth.state().getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8))
                || oauth.expiresAt().isBefore(Instant.now()) || code == null || code.isBlank()) {
            throw new IllegalArgumentException("Сеанс входа через VK истёк. Попробуйте ещё раз.");
        }
        session.removeAttribute(STATE_SESSION);
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "authorization_code"); form.add("code", code); form.add("client_id", clientId);
            form.add("redirect_uri", redirectUri); form.add("code_verifier", oauth.verifier());
            if (!clientSecret.isBlank()) form.add("client_secret", clientSecret);
            if (deviceId != null && !deviceId.isBlank()) form.add("device_id", deviceId);
            String tokenBody = client.post().uri(tokenUrl).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(String.class);
            JsonNode token = mapper.readTree(tokenBody);
            String subject = text(token, "user_id", "sub", "id");
            String email = text(token, "email");
            String accessToken = text(token, "access_token");
            JsonNode profile = null;
            if (accessToken != null && !accessToken.isBlank()) {
                MultiValueMap<String, String> infoForm = new LinkedMultiValueMap<>();
                infoForm.add("access_token", accessToken); infoForm.add("client_id", clientId);
                profile = mapper.readTree(client.post().uri(userInfoUrl).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(infoForm)
                        .retrieve().body(String.class));
                if (subject == null) subject = text(profile, "user_id", "id", "sub");
                if (email == null) email = text(profile, "email");
            }
            if (subject == null || subject.isBlank()) throw new IllegalArgumentException("VK не вернул идентификатор пользователя");
            String name = fullName(profile == null ? token : profile);
            VkProfile result = new VkProfile(subject, email == null ? null : email.trim().toLowerCase(), name, oauth.purpose(), oauth.userId());
            session.setAttribute(PROFILE_SESSION, result);
            return result;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Не удалось получить данные от VK. Попробуйте ещё раз.");
        }
    }

    public Optional<ExternalIdentity> findIdentity(String subject) { return identities.findByProviderAndProviderSubject(PROVIDER, subject); }
    public Optional<ExternalIdentity> findVkIdentity(User user) { return identities.findByUserIdAndProvider(user.getId(), PROVIDER); }

    @Transactional
    public ExternalIdentity link(User user, VkProfile profile) {
        return identities.findByProviderAndProviderSubject(PROVIDER, profile.subject()).map(existing -> {
            if (!existing.getUser().getId().equals(user.getId())) throw new IllegalArgumentException("Этот VK ID уже привязан к другому аккаунту");
            existing.recordLogin(); return identities.save(existing);
        }).orElseGet(() -> identities.save(new ExternalIdentity(user, PROVIDER, profile.subject(), profile.email())));
    }

    @Transactional
    public User createAccount(VkProfile profile, Role role) {
        if (findIdentity(profile.subject()).isPresent()) throw new IllegalArgumentException("Этот VK ID уже используется");
        if (profile.email() == null || profile.email().isBlank())
            throw new IllegalArgumentException("VK не передал email. Разрешите доступ к email в VK или зарегистрируйтесь обычным способом.");
        if (accounts.requireByIdentifierOrNull(profile.email()) != null)
            throw new IllegalArgumentException("Эта почта уже используется в другом аккаунте");
        User user = accounts.registerFromExternalIdentity(
                profile.displayName(), generateUsername(profile.subject()), profile.email(), role, true);
        link(user, profile);
        return user;
    }

    @Transactional
    public void unlink(User user) {
        ExternalIdentity identity = findVkIdentity(user).orElseThrow(() -> new IllegalArgumentException("VK не привязан"));
        if (!user.hasPassword()) throw new IllegalArgumentException("Сначала создайте пароль — VK остаётся единственным способом входа");
        identities.delete(identity);
    }

    @Transactional
    public void recordLogin(ExternalIdentity identity) { identity.recordLogin(); identities.save(identity); }

    public VkProfile pending(HttpSession session) {
        Object value = session.getAttribute(PROFILE_SESSION);
        return value instanceof VkProfile profile ? profile : null;
    }
    public void clearPending(HttpSession session) { session.removeAttribute(PROFILE_SESSION); }

    private String randomUrl(int bytes) { byte[] value = new byte[bytes]; random.nextBytes(value); return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private String sha256(String value) { try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII))); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private String generateUsername(String subject) {
        String base = "vk_" + sha256(subject).replace("-", "").replace("_", "")
                .substring(0, 12).toLowerCase();
        if (accounts.requireByIdentifierOrNull(base) == null) return base;
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidate = base + "_" + randomUrl(4).replace("-", "").replace("_", "")
                    .substring(0, 5).toLowerCase();
            if (accounts.requireByIdentifierOrNull(candidate) == null) return candidate;
        }
        throw new IllegalStateException("Не удалось создать уникальный логин");
    }
    private String text(JsonNode node, String... names) {
        if (node == null) return null;
        for (String name : names) { JsonNode value = node.path(name); if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) return value.asText(); }
        JsonNode user = node.path("user");
        if (!user.isMissingNode()) for (String name : names) { JsonNode value = user.path(name); if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) return value.asText(); }
        return null;
    }
    private String fullName(JsonNode node) {
        String first = text(node, "first_name", "firstName"), last = text(node, "last_name", "lastName");
        String value = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return value.isBlank() ? "Пользователь VK" : value.substring(0, Math.min(80, value.length()));
    }

    public enum Purpose { LOGIN, LINK }
    public record OAuthState(String state, String verifier, Purpose purpose, Long userId, Instant expiresAt) implements Serializable { }
    public record VkProfile(String subject, String email, String displayName, Purpose purpose, Long linkUserId) implements Serializable { }
}

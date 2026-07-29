package ru.repethelper;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.repethelper.domain.*;
import ru.repethelper.repository.*;
import ru.repethelper.service.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FullFlowIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.storage-path", () -> "target/test-uploads");
        registry.add("app.teacher.username", () -> "teacher");
        registry.add("app.teacher.password", () -> "secure-password");
        registry.add("app.teacher.name", () -> "Иван Петрович");
        registry.add("app.teacher.code", () -> "teacher_code");
        registry.add("app.account-gate-enabled", () -> "true");
        registry.add("app.notifications.initial-delay-ms", () -> "3600000");
    }

    @Autowired WebApplicationContext context;
    @Autowired AccountService accounts;
    @Autowired ConnectionService connections;
    @Autowired LessonService lessons;
    @Autowired AttachmentService attachments;
    @Autowired TeacherProfileService profiles;
    @Autowired UserRepository users;
    @Autowired LessonRepository lessonRepository;
    @Autowired LessonSeriesRepository lessonSeriesRepository;
    @Autowired ConnectionRequestRepository requestRepository;
    @Autowired StudentRemovalService studentRemovals;
    @Autowired WhiteboardService whiteboards;
    @Autowired AccountTokenService accountTokens;
    @Autowired LoginAttemptService loginAttempts;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired VkAuthService vkAuth;
    @Autowired ExternalIdentityRepository externalIdentities;
    @Autowired EmailNotificationRepository emailNotifications;
    @Autowired LessonReminderService lessonReminders;
    private MockMvc mvc;

    @BeforeEach void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test void registrationIsPublicAndTeacherAreaIsProtected() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/login")).andExpect(status().isOk());
        mvc.perform(get("/teacher")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/teacher").with(user("student").roles("STUDENT"))).andExpect(status().isForbidden());
        mvc.perform(post("/register").with(csrf())
                .param("displayName", "Новый Ученик").param("username", "new_student")
                .param("email", "new.student@example.test").param("password", "password123")
                .param("passwordConfirmation", "password123").param("role", "STUDENT")
                .param("termsAccepted", "true").param("personalDataAccepted", "true"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/verify-email/pending?welcome"));
        assertThat(users.findByUsernameIgnoreCase("new_student")).isPresent();
    }

    @Test
    void vkRegistrationOnlyAsksRoleAndCreatesVkOnlyAccount() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(VkAuthService.PROFILE_SESSION, new VkAuthService.VkProfile(
                "vk-fast-registration-1", "vk.fast.registration@example.test",
                "Ефим Ефимов", VkAuthService.Purpose.LOGIN, null));

        mvc.perform(get("/auth/vk/onboarding").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Кем вы будете пользоваться RepetHelper?")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("name=\"email\""))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("name=\"username\""))));

        var registration = mvc.perform(post("/auth/vk/onboarding").session(session).with(csrf())
                        .param("role", "STUDENT"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/student"))
                .andReturn();

        User user = users.findByEmailIgnoreCase("vk.fast.registration@example.test").orElseThrow();
        assertThat(user.getUsername()).startsWith("vk_");
        assertThat(user.getDisplayName()).isEqualTo("Ефим Ефимов");
        assertThat(user.hasPassword()).isFalse();
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.hasAccepted(AccountService.TERMS_VERSION, AccountService.PRIVACY_VERSION)).isTrue();
        assertThat(externalIdentities.findByProviderAndProviderSubject("VK", "vk-fast-registration-1")).isPresent();
        assertThat(accountTokens.createPasswordReset(user.getEmail())).isEmpty();

        MockHttpSession authenticated = (MockHttpSession) registration.getRequest().getSession(false);
        mvc.perform(get("/student").session(authenticated))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Привет, Ефим Ефимов!")));

        mvc.perform(post("/login").with(csrf())
                        .param("username", user.getEmail()).param("password", "AnyPassword123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void vkRegistrationRejectsExistingEmailAndMissingEmailWithoutPartialAccount() {
        accounts.register("Обычный пользователь", "ordinary_collision",
                "shared.vk.email@example.test", "OrdinaryPassword123", Role.STUDENT, true);
        long usersBefore = users.count();

        var collision = new VkAuthService.VkProfile("vk-email-collision",
                "shared.vk.email@example.test", "Другой пользователь", VkAuthService.Purpose.LOGIN, null);
        assertThatThrownBy(() -> vkAuth.createAccount(collision, Role.STUDENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Эта почта уже используется в другом аккаунте");
        assertThat(users.count()).isEqualTo(usersBefore);
        assertThat(externalIdentities.findByProviderAndProviderSubject("VK", "vk-email-collision")).isEmpty();

        var noEmail = new VkAuthService.VkProfile("vk-without-email", null,
                "Пользователь без почты", VkAuthService.Purpose.LOGIN, null);
        assertThatThrownBy(() -> vkAuth.createAccount(noEmail, Role.TEACHER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VK не передал email");
        assertThat(users.count()).isEqualTo(usersBefore);
    }

    @Test
    void vkTeacherRegistrationCreatesTeacherProfileAutomatically() {
        User teacher = vkAuth.createAccount(new VkAuthService.VkProfile(
                "vk-teacher-registration", "vk.teacher@example.test",
                "Новый преподаватель", VkAuthService.Purpose.LOGIN, null), Role.TEACHER);

        assertThat(teacher.getRole()).isEqualTo(Role.TEACHER);
        assertThat(teacher.hasPassword()).isFalse();
        assertThat(profiles.requireFor(teacher).getInviteCode()).matches("T-[A-Z2-9]{8}");
        assertThat(externalIdentities.findByProviderAndProviderSubject(
                "VK", "vk-teacher-registration")).isPresent();
    }

    @Test void completeTutorWorkflowAndOwnershipProtection() throws Exception {
        User teacher = accounts.requireByUsername("teacher");
        profiles.update(teacher, "Иван Сергеевич");
        assertThat(accounts.requireByUsername("teacher").getDisplayName()).isEqualTo("Иван Сергеевич");
        User student = accounts.registerStudent("Алексей Смирнов", "alex_flow", "password123");
        connections.send(student, "teacher_code");
        ConnectionRequest request = requestRepository.findByStudentOrderByCreatedAtDesc(student).getFirst();
        connections.process(teacher, request.getId(), true);
        assertThat(connections.isAccepted(student)).isTrue();

        Lesson lesson = lessons.create(teacher, student.getId(), LocalDateTime.of(2026, 8, 10, 17, 0), 60);
        lessons.updateMaterials(teacher, lesson.getId(), "Решить задачи 1–5", "Разобрали алгоритмы");
        attachments.store(teacher, lesson.getId(), AttachmentCategory.HOMEWORK, List.of(
                new MockMultipartFile("files", "homework.pdf", "application/pdf", "content".getBytes(StandardCharsets.UTF_8))));

        mvc.perform(get("/teacher").with(user("teacher").roles("TEACHER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Алексей Смирнов")));
        mvc.perform(get("/student").with(user("alex_flow").roles("STUDENT")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Мои занятия")));
        mvc.perform(get("/lessons/{id}", lesson.getId()).with(user("alex_flow").roles("STUDENT")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Решить задачи 1–5")));

        User outsider = accounts.registerStudent("Чужой Ученик", "outsider_flow", "password123");
        mvc.perform(get("/lessons/{id}", lesson.getId()).with(user(outsider.getUsername()).roles("STUDENT")))
                .andExpect(status().isForbidden());

        MockMultipartFile executable = new MockMultipartFile("files", "virus.exe", "application/octet-stream", new byte[]{1});
        assertThatThrownBy(() -> attachments.store(teacher, lesson.getId(), AttachmentCategory.HOMEWORK, List.of(executable)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("запрещён");

        lessons.delete(teacher, lesson.getId());
        assertThat(lessonRepository.findById(lesson.getId())).isEmpty();
    }

    @Test
    void invitationLinkKeepsItsContextAndTeacherCanRemoveOnlyTheirStudentData() throws Exception {
        User teacher = accounts.register("Invite Teacher", "invite_teacher", "invite.teacher@example.test",
                "InvitePassword123", Role.TEACHER, true);
        User student = accounts.register("Invite Student", "invite_student", "invite.student@example.test",
                "InvitePassword123", Role.STUDENT, true);
        student.verifyEmail();
        student = users.save(student);
        String code = profiles.requireFor(teacher).getInviteCode();
        MockHttpSession session = new MockHttpSession();

        mvc.perform(get("/invite/{code}", code).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Invite Teacher")));
        assertThat(session.getAttribute(InvitationService.PENDING_INVITE_CODE)).isEqualTo(code);

        var principal = accounts.principalFor(student);
        mvc.perform(post("/invite/{code}/request", code).session(session).with(csrf())
                        .with(authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                principal, null, principal.getAuthorities()))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/student"));
        assertThat(requestRepository.findByStudentOrderByCreatedAtDesc(student)).singleElement()
                .extracting(ConnectionRequest::getStatus).isEqualTo(ConnectionStatus.PENDING);

        ConnectionRequest request = requestRepository.findByStudentOrderByCreatedAtDesc(student).getFirst();
        connections.process(teacher, request.getId(), true);
        Lesson lesson = lessons.create(teacher, student.getId(), LocalDateTime.of(2026, 9, 2, 17, 0),
                60, LessonRecurrence.WEEKLY);
        whiteboards.getOrCreate(teacher, lesson);
        attachments.store(teacher, lesson.getId(), AttachmentCategory.HOMEWORK, List.of(
                new MockMultipartFile("files", "task.pdf", "application/pdf", "task".getBytes(StandardCharsets.UTF_8))));

        StudentRemovalService.RemovalPreview preview = studentRemovals.preview(teacher, student.getId());
        assertThat(preview.lessonCount()).isGreaterThanOrEqualTo(1);
        assertThat(preview.seriesCount()).isEqualTo(1);
        assertThat(preview.attachmentCount()).isEqualTo(1);
        assertThat(preview.boardCount()).isEqualTo(1);
        studentRemovals.remove(teacher, student.getId());

        assertThat(users.findById(student.getId())).isPresent();
        assertThat(requestRepository.findByStudentOrderByCreatedAtDesc(student)).isEmpty();
        assertThat(lessonRepository.findByTeacherAndStudentOrderByStartAtAsc(teacher, student)).isEmpty();
        assertThat(lessonSeriesRepository.findByTeacherAndStudent(teacher, student)).isEmpty();
    }

    @Test void weeklySeriesCanBeRescheduledAndDeletedFromSelectedLesson() throws Exception {
        User teacher = accounts.requireByUsername("teacher");
        User student = accounts.registerStudent("Ученик серии", "weekly_student", "password123");
        connections.send(student, "teacher_code");
        ConnectionRequest request = requestRepository.findByStudentOrderByCreatedAtDesc(student).getFirst();
        connections.process(teacher, request.getId(), true);

        Lesson first = lessons.create(teacher, student.getId(),
                LocalDateTime.of(2026, 8, 5, 17, 0), 60, LessonRecurrence.WEEKLY);
        lessons.forMonth(teacher, java.time.YearMonth.of(2026, 8));
        var occurrences = lessonRepository.findBySeriesIdAndOccurrenceIndexGreaterThanEqualOrderByOccurrenceIndexAsc(
                first.getSeries().getId(), 0);
        assertThat(occurrences).hasSize(4);

        Lesson second = occurrences.get(1);
        lessons.reschedule(teacher, second.getId(), LocalDateTime.of(2026, 8, 13, 17, 0),
                60, LessonChangeScope.SINGLE);
        lessons.reschedule(teacher, second.getId(), LocalDateTime.of(2026, 8, 14, 18, 30),
                90, LessonChangeScope.FOLLOWING);
        occurrences = lessonRepository.findBySeriesIdAndOccurrenceIndexGreaterThanEqualOrderByOccurrenceIndexAsc(
                first.getSeries().getId(), 0);
        ZoneId moscow = ZoneId.of("Europe/Moscow");
        assertThat(LocalDateTime.ofInstant(occurrences.get(0).getStartAt(), moscow))
                .isEqualTo(LocalDateTime.of(2026, 8, 5, 17, 0));
        assertThat(LocalDateTime.ofInstant(occurrences.get(1).getStartAt(), moscow))
                .isEqualTo(LocalDateTime.of(2026, 8, 14, 18, 30));
        assertThat(LocalDateTime.ofInstant(occurrences.get(2).getStartAt(), moscow))
                .isEqualTo(LocalDateTime.of(2026, 8, 21, 18, 30));
        assertThat(occurrences.subList(1, occurrences.size()))
                .allMatch(item -> item.getDurationMinutes() == 90);

        lessons.delete(teacher, occurrences.get(1).getId(), LessonChangeScope.SINGLE);
        lessons.forMonth(teacher, java.time.YearMonth.of(2026, 8));
        occurrences = lessonRepository.findBySeriesIdAndOccurrenceIndexGreaterThanEqualOrderByOccurrenceIndexAsc(
                first.getSeries().getId(), 0);
        assertThat(occurrences).extracting(Lesson::getOccurrenceIndex).containsExactly(0, 2, 3);

        lessons.delete(teacher, occurrences.get(1).getId(), LessonChangeScope.FOLLOWING);
        lessons.forMonth(teacher, java.time.YearMonth.of(2026, 10));
        occurrences = lessonRepository.findBySeriesIdAndOccurrenceIndexGreaterThanEqualOrderByOccurrenceIndexAsc(
                first.getSeries().getId(), 0);
        assertThat(occurrences).extracting(Lesson::getOccurrenceIndex).containsExactly(0);

        mvc.perform(get("/teacher").with(user("teacher").roles("TEACHER")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Каждую неделю")));
        mvc.perform(get("/lessons/{id}", first.getId()).with(user("teacher").roles("TEACHER")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("к этому и всем последующим")));
    }

    @Test void teachersAreStrictlyIsolatedAndPasswordsAreHashed() {
        User firstTeacher = accounts.requireByUsername("teacher");
        User secondTeacher = accounts.register("Второй Преподаватель", "teacher_two",
                "teacher.two@example.test", "anotherPassword123", Role.TEACHER, true);
        assertThat(secondTeacher.getPasswordHash()).isNotEqualTo("anotherPassword123");
        assertThat(secondTeacher.getPasswordHash()).startsWith("$2");
        assertThat(profiles.requireFor(secondTeacher).getInviteCode()).matches("T-[A-Z2-9]{8}");
        assertThatThrownBy(() -> accounts.register("Дубликат", "different_login",
                "TEACHER.TWO@example.test", "anotherPassword456", Role.TEACHER, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Email");

        User student = accounts.register("Ученик второго", "second_student",
                "second.student@example.test", "studentPassword123", Role.STUDENT, true);
        connections.send(student, profiles.requireFor(secondTeacher).getInviteCode());
        ConnectionRequest request = requestRepository.findByStudentOrderByCreatedAtDesc(student).getFirst();
        connections.process(secondTeacher, request.getId(), true);
        Lesson lesson = lessons.create(secondTeacher, student.getId(),
                LocalDateTime.of(2026, 10, 1, 16, 0), 60);

        assertThatThrownBy(() -> lessons.requireTeacherLesson(firstTeacher, lesson.getId()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(ex -> assertThat(((org.springframework.web.server.ResponseStatusException) ex)
                        .getStatusCode().value()).isEqualTo(403));
        assertThat(lessons.forMonth(firstTeacher, java.time.YearMonth.of(2026, 10)))
                .doesNotContain(lesson);
    }

    @Test void emailVerificationResetAndBruteForceProtectionWork() {
        User user = accounts.register("Проверка безопасности", "security_student",
                "security.student@example.test", "InitialPassword123", Role.STUDENT, true);
        assertThat(passwordEncoder.matches("InitialPassword123", user.getPasswordHash())).isTrue();
        assertThat(user.getPasswordHash()).isNotEqualTo("InitialPassword123");

        String verification = accountTokens.createVerification(user);
        assertThat(verification).matches("\\d{6}");
        assertThat(accountTokens.verifyEmail(user, verification)).isTrue();
        assertThat(accountTokens.verifyEmail(user, verification)).isFalse();
        assertThat(accounts.requireByUsername("security_student").isEmailVerified()).isTrue();

        var delivery = accountTokens.createPasswordReset("SECURITY.STUDENT@EXAMPLE.TEST").orElseThrow();
        assertThat(delivery.code()).matches("\\d{6}");
        assertThat(accountTokens.resetPassword("security_student", delivery.code(), "ChangedPassword123")).isTrue();
        assertThat(accountTokens.resetPassword("security_student", delivery.code(), "AnotherPassword123")).isFalse();
        User changed = accounts.requireByUsername("security_student");
        assertThat(passwordEncoder.matches("ChangedPassword123", changed.getPasswordHash())).isTrue();
        assertThat(changed.getAuthVersion()).isEqualTo(1);

        for (int i = 0; i < 5; i++) loginAttempts.loginFailed("security_student", "192.0.2.10");
        assertThat(loginAttempts.loginAllowed("security_student", "192.0.2.10")).isFalse();

        for (int i = 0; i < 5; i++) {
            assertThat(loginAttempts.verificationResendAllowed("security_student", "192.0.2.20")).isTrue();
        }
        assertThat(loginAttempts.verificationResendAllowed("security_student", "192.0.2.20")).isFalse();
    }

    @Test
    void legacyAccountCompletionPersistsAcrossLogoutAndFinishesWithCode() throws Exception {
        User legacy = accounts.registerStudent("Старый пользователь", "legacy_auth_flow", "LegacyPassword123");

        var firstLogin = mvc.perform(post("/login").with(csrf())
                        .param("username", "legacy_auth_flow").param("password", "LegacyPassword123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/account/consent"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) firstLogin.getRequest().getSession(false);

        mvc.perform(post("/account/consent").session(session).with(csrf())
                        .param("email", "legacy.auth@example.test")
                        .param("termsAccepted", "true")
                        .param("personalDataAccepted", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/verify-email/pending"));

        User completed = accounts.requireByUsername("legacy_auth_flow");
        assertThat(completed.getEmail()).isEqualTo("legacy.auth@example.test");
        assertThat(completed.hasAccepted(AccountService.TERMS_VERSION, AccountService.PRIVACY_VERSION)).isTrue();
        assertThat(completed.isEmailVerified()).isFalse();
        String code = accountTokens.createVerification(completed);

        mvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        var secondLogin = mvc.perform(post("/login").with(csrf())
                        .param("username", "LEGACY.AUTH@EXAMPLE.TEST").param("password", "LegacyPassword123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/verify-email/pending"))
                .andReturn();
        MockHttpSession resumedSession = (MockHttpSession) secondLogin.getRequest().getSession(false);

        mvc.perform(post("/verify-email").session(resumedSession).with(csrf()).param("code", code))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/student"));
        assertThat(accounts.requireByUsername("legacy_auth_flow").isEmailVerified()).isTrue();

        mvc.perform(post("/logout").session(resumedSession).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/login").with(csrf())
                        .param("username", "legacy_auth_flow").param("password", "LegacyPassword123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/student"));
    }

    @Test
    void newRegistrationCanBeInterruptedAndResumedAtEmailCodeStep() throws Exception {
        var registration = mvc.perform(post("/register").with(csrf())
                        .param("displayName", "Новый пользователь")
                        .param("username", "interrupted_registration")
                        .param("email", "interrupted@example.test")
                        .param("password", "RegistrationPassword123")
                        .param("passwordConfirmation", "RegistrationPassword123")
                        .param("role", "STUDENT")
                        .param("termsAccepted", "true")
                        .param("personalDataAccepted", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/verify-email/pending?welcome"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) registration.getRequest().getSession(false);
        User user = accounts.requireByUsername("interrupted_registration");
        String code = accountTokens.createVerification(user);

        mvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());
        var login = mvc.perform(post("/login").with(csrf())
                        .param("username", "interrupted_registration")
                        .param("password", "RegistrationPassword123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/verify-email/pending"))
                .andReturn();
        MockHttpSession resumed = (MockHttpSession) login.getRequest().getSession(false);
        mvc.perform(post("/verify-email").session(resumed).with(csrf()).param("code", code))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/student"));
    }

    @Test
    void verificationCodeIsSixDigitsOneTimeAndLocksAfterFiveFailures() {
        User user = accounts.register("Проверка кода", "verification_code_limits",
                "verification.code@example.test", "VerificationPassword123", Role.STUDENT, true);
        String firstCode = accountTokens.createVerification(user);
        assertThat(firstCode).matches("\\d{6}");
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(accountTokens.verifyEmail(user, "999999".equals(firstCode) ? "888888" : "999999")).isFalse();
        }
        assertThat(accountTokens.verifyEmail(user, firstCode)).isFalse();

        String replacementCode = accountTokens.createVerification(user);
        assertThat(replacementCode).matches("\\d{6}").isNotEqualTo(firstCode);
        assertThat(accountTokens.verifyEmail(user, replacementCode)).isTrue();
        assertThat(accountTokens.verifyEmail(user, replacementCode)).isFalse();
    }

    @Test
    void passwordResetUsesIdentifierAndSixDigitOneTimeCode() throws Exception {
        User user = accounts.register("Сброс пароля", "password_reset_code",
                "password.reset@example.test", "OriginalPassword123", Role.STUDENT, true);
        String verificationCode = accountTokens.createVerification(user);
        assertThat(accountTokens.verifyEmail(user, verificationCode)).isTrue();

        var request = mvc.perform(post("/forgot-password").with(csrf())
                        .param("identifier", "PASSWORD.RESET@EXAMPLE.TEST"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reset-password?requested"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) request.getRequest().getSession(false);
        String resetCode = accountTokens.createPasswordReset("password_reset_code").orElseThrow().code();

        mvc.perform(post("/reset-password").session(session).with(csrf())
                        .param("identifier", "password_reset_code")
                        .param("code", resetCode)
                        .param("password", "ChangedPassword123")
                        .param("passwordConfirmation", "DifferentPassword123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reset-password"));

        mvc.perform(post("/reset-password").session(session).with(csrf())
                        .param("identifier", "password_reset_code")
                        .param("code", resetCode)
                        .param("password", "ChangedPassword123")
                        .param("passwordConfirmation", "ChangedPassword123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?reset"));

        User changed = accounts.requireByUsername("password_reset_code");
        assertThat(passwordEncoder.matches("ChangedPassword123", changed.getPasswordHash())).isTrue();
        assertThat(accountTokens.resetPassword("password_reset_code", resetCode, "AnotherPassword123")).isFalse();
    }

    @Test
    void studentCanConnectToSeveralTeachersWithoutBreakingIsolation() {
        User teacherA = verified(accounts.register("Преподаватель A", "multi_teacher_a",
                "multi.teacher.a@example.test", "TeacherPassword123", Role.TEACHER, true));
        User teacherB = verified(accounts.register("Преподаватель B", "multi_teacher_b",
                "multi.teacher.b@example.test", "TeacherPassword123", Role.TEACHER, true));
        User student = verified(accounts.register("Общий ученик", "multi_student",
                "multi.student@example.test", "StudentPassword123", Role.STUDENT, true));

        connections.send(student, profiles.requireFor(teacherA).getInviteCode());
        connections.send(student, profiles.requireFor(teacherB).getInviteCode());
        List<ConnectionRequest> pending = requestRepository.findByStudentOrderByCreatedAtDesc(student);
        assertThat(pending).hasSize(2).allMatch(item -> item.getStatus() == ConnectionStatus.PENDING);

        pending.forEach(item -> connections.process(item.getTeacher(), item.getId(), true));
        assertThat(requestRepository.findByStudentOrderByCreatedAtDesc(student))
                .hasSize(2).allMatch(item -> item.getStatus() == ConnectionStatus.ACCEPTED);
        assertThatThrownBy(() -> connections.send(student, profiles.requireFor(teacherA).getInviteCode()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("этому преподавателю");

        Lesson lessonA = lessons.create(teacherA, student.getId(),
                LocalDateTime.of(2026, 11, 2, 16, 0), 60);
        Lesson lessonB = lessons.create(teacherB, student.getId(),
                LocalDateTime.of(2026, 11, 3, 17, 0), 60);
        assertThat(lessons.forMonth(student, java.time.YearMonth.of(2026, 11)))
                .extracting(Lesson::getId).contains(lessonA.getId(), lessonB.getId());
        assertThatThrownBy(() -> lessons.requireTeacherLesson(teacherA, lessonB.getId()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(ex -> assertThat(((org.springframework.web.server.ResponseStatusException) ex)
                        .getStatusCode().value()).isEqualTo(403));

        studentRemovals.remove(teacherA, student.getId());
        assertThat(requestRepository.existsByStudentAndTeacherAndStatus(
                student, teacherA, ConnectionStatus.ACCEPTED)).isFalse();
        assertThat(requestRepository.existsByStudentAndTeacherAndStatus(
                student, teacherB, ConnectionStatus.ACCEPTED)).isTrue();
        assertThat(lessonRepository.findById(lessonA.getId())).isEmpty();
        assertThat(lessonRepository.findById(lessonB.getId())).isPresent();
        assertThat(users.findById(student.getId())).isPresent();
    }

    @Test
    void notificationOutboxDeduplicatesHomeworkAndFourHourReminder() {
        User teacher = verified(accounts.register("Почтовый преподаватель", "mail_teacher",
                "mail.teacher@example.test", "TeacherPassword123", Role.TEACHER, true));
        User student = verified(accounts.register("Почтовый ученик", "mail_student",
                "mail.student@example.test", "StudentPassword123", Role.STUDENT, true));
        connections.send(student, profiles.requireFor(teacher).getInviteCode());
        ConnectionRequest request = requestRepository.findByStudentOrderByCreatedAtDesc(student).getFirst();
        connections.process(teacher, request.getId(), true);

        Instant start = Instant.now().plus(Duration.ofHours(3));
        Lesson lesson = lessons.create(teacher, student.getId(),
                LocalDateTime.ofInstant(start, ZoneId.of("Europe/Moscow")), 60);
        lessons.updateMaterials(teacher, lesson.getId(), "Решить задачи 1–5", null);
        attachments.store(teacher, lesson.getId(), AttachmentCategory.HOMEWORK, List.of(
                new MockMultipartFile("files", "tasks.pdf", "application/pdf", new byte[]{1, 2, 3})));
        lessonReminders.enqueueUpcomingReminders();
        lessonReminders.enqueueUpcomingReminders();

        assertThat(emailNotifications.findAll().stream()
                .filter(item -> item.getType() == EmailNotificationType.HOMEWORK_UPDATED)
                .filter(item -> item.getRecipientEmail().equals(student.getEmail())).toList()).hasSize(1);
        assertThat(emailNotifications.findAll().stream()
                .filter(item -> item.getType() == EmailNotificationType.LESSON_REMINDER)
                .filter(item -> item.getRecipientEmail().equals(student.getEmail())).toList()).hasSize(1);
        assertThat(emailNotifications.findAll())
                .anyMatch(item -> item.getType() == EmailNotificationType.CONNECTION_REQUEST_RECEIVED
                        && item.getRecipientEmail().equals(teacher.getEmail()))
                .anyMatch(item -> item.getType() == EmailNotificationType.CONNECTION_ACCEPTED
                        && item.getRecipientEmail().equals(student.getEmail()))
                .anyMatch(item -> item.getType() == EmailNotificationType.LESSON_CREATED
                        && item.getRecipientEmail().equals(student.getEmail()));
    }

    private User verified(User user) {
        user.verifyEmail();
        return users.save(user);
    }
}

package ru.tutor.codeclass;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.tutor.codeclass.domain.*;
import ru.tutor.codeclass.repository.*;
import ru.tutor.codeclass.service.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
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
    }

    @Autowired WebApplicationContext context;
    @Autowired AccountService accounts;
    @Autowired ConnectionService connections;
    @Autowired LessonService lessons;
    @Autowired AttachmentService attachments;
    @Autowired TeacherProfileService profiles;
    @Autowired UserRepository users;
    @Autowired LessonRepository lessonRepository;
    @Autowired ConnectionRequestRepository requestRepository;
    private MockMvc mvc;

    @BeforeEach void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test void registrationIsPublicAndTeacherAreaIsProtected() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk());
        mvc.perform(get("/teacher")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/teacher").with(user("student").roles("STUDENT"))).andExpect(status().isForbidden());
        mvc.perform(post("/register").with(csrf())
                .param("displayName", "Новый Ученик").param("username", "new_student").param("password", "password123"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/student?welcome"));
        assertThat(users.findByUsernameIgnoreCase("new_student")).isPresent();
    }

    @Test void completeTutorWorkflowAndOwnershipProtection() throws Exception {
        User teacher = accounts.requireByUsername("teacher");
        profiles.update(teacher, "Иван Сергеевич", "teacher_code");
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
}

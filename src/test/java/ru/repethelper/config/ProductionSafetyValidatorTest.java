package ru.repethelper.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSafetyValidatorTest {
    @Test
    void acceptsStrongSecretsAndSingleHttpsOrigin() {
        ProductionSafetyValidator validator = new ProductionSafetyValidator(
                "database-password-with-32-characters",
                "teacher",
                "teacher-password-with-32-characters",
                "Преподаватель",
                "private-student-code",
                "https://repethelper.example.ru");

        assertThatCode(() -> validator.run(null)).doesNotThrowAnyException();
    }

    @Test
    void rejectsDevelopmentTeacherPassword() {
        ProductionSafetyValidator validator = new ProductionSafetyValidator(
                "database-password-with-32-characters",
                "teacher",
                "change-me-now",
                "Преподаватель",
                "private-student-code",
                "https://repethelper.example.ru");

        assertThatThrownBy(() -> validator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TEACHER_PASSWORD");
    }

    @Test
    void rejectsNonHttpsOrMultipleOrigins() {
        ProductionSafetyValidator http = new ProductionSafetyValidator(
                "database-password-with-32-characters",
                "teacher",
                "teacher-password-with-32-characters",
                "Преподаватель",
                "private-student-code",
                "http://repethelper.example.ru");
        ProductionSafetyValidator multiple = new ProductionSafetyValidator(
                "database-password-with-32-characters",
                "teacher",
                "teacher-password-with-32-characters",
                "Преподаватель",
                "private-student-code",
                "https://one.example.ru,https://two.example.ru");

        assertThatThrownBy(() -> http.run(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> multiple.run(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsTeacherValuesThatDoNotFitDatabaseSchema() {
        ProductionSafetyValidator longCode = new ProductionSafetyValidator(
                "database-password-with-32-characters",
                "teacher",
                "teacher-password-with-32-characters",
                "Преподаватель",
                "a".repeat(31),
                "https://repethelper.example.ru");

        assertThatThrownBy(() -> longCode.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TEACHER_CODE");
    }

    @Test
    void rejectsUnchangedExampleSecretsAndDomain() {
        ProductionSafetyValidator placeholders = new ProductionSafetyValidator(
                "replace-with-at-least-16-random-characters",
                "teacher",
                "replace-with-at-least-16-random-characters",
                "Преподаватель",
                "replace-private-code",
                "https://repethelper.your-domain.ru");

        assertThatThrownBy(() -> placeholders.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("POSTGRES_PASSWORD");
    }
}

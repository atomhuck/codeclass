package ru.tutor.codeclass.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class LessonTest {
    private final User student = new User("student", "hash", "Ученик", Role.STUDENT);

    @Test void lessonBecomesPastAfterItsEnd() {
        Lesson lesson = new Lesson(student, Instant.parse("2026-07-23T10:00:00Z"), 60);
        assertThat(lesson.isPast(Instant.parse("2026-07-23T10:59:59Z"))).isFalse();
        assertThat(lesson.isPast(Instant.parse("2026-07-23T11:00:00Z"))).isTrue();
    }

    @Test void cancelledLessonIsNotClassifiedAsPast() {
        Lesson lesson = new Lesson(student, Instant.parse("2026-07-23T10:00:00Z"), 60);
        lesson.cancel();
        assertThat(lesson.isPast(Instant.parse("2026-07-24T10:00:00Z"))).isFalse();
        assertThat(lesson.getStatus()).isEqualTo(LessonStatus.CANCELLED);
    }
}

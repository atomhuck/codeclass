package ru.repethelper.service;

import org.junit.jupiter.api.Test;
import ru.repethelper.domain.*;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CalendarServiceTest {
    @Test void buildsSixWeekMondayFirstCalendarAndPlacesLesson() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-23T10:00:00Z"), ZoneOffset.UTC);
        CalendarService service = new CalendarService("Europe/Moscow", clock);
        User teacher = new User("teacher", "hash", "Преподаватель", Role.TEACHER);
        User student = new User("student", "hash", "Ученик", Role.STUDENT);
        Lesson lesson = new Lesson(teacher, student, Instant.parse("2026-07-23T14:00:00Z"), 60);

        var calendar = service.build(YearMonth.of(2026, 7), List.of(lesson));

        assertThat(calendar.days()).hasSize(42);
        assertThat(calendar.days().getFirst().date().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(calendar.days()).filteredOn(day -> day.date().equals(LocalDate.of(2026, 7, 23)))
                .singleElement().satisfies(day -> {
                    assertThat(day.today()).isTrue();
                    assertThat(day.lessons()).containsExactly(lesson);
                });
    }
}

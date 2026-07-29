package ru.repethelper.web.view;

import ru.repethelper.domain.Lesson;
import ru.repethelper.domain.LessonStatus;
import java.time.LocalDate;
import java.util.List;

public record CalendarDay(LocalDate date, boolean currentMonth, boolean today, List<Lesson> lessons) {
    public boolean hasScheduledLessons() {
        return lessons.stream().anyMatch(lesson -> lesson.getStatus() == LessonStatus.SCHEDULED);
    }
}

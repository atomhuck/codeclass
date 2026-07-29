package ru.repethelper.web.view;

import ru.repethelper.domain.Lesson;
import ru.repethelper.domain.LessonStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record CalendarDay(LocalDate date, boolean currentMonth, boolean today, List<Lesson> lessons, Instant referenceTime) {
    public boolean hasScheduledLessons() {
        return lessons.stream().anyMatch(lesson -> lesson.getStatus() == LessonStatus.SCHEDULED);
    }

    public boolean hasUpcomingScheduledLessons() {
        return lessons.stream().anyMatch(lesson -> lesson.getStatus() == LessonStatus.SCHEDULED && !lesson.isPast(referenceTime));
    }

    public boolean hasPastLessons() {
        return lessons.stream().anyMatch(lesson -> lesson.getStatus() == LessonStatus.CANCELLED || lesson.isPast(referenceTime));
    }

    public boolean past(Lesson lesson) {
        return lesson.getStatus() == LessonStatus.CANCELLED || lesson.isPast(referenceTime);
    }
}

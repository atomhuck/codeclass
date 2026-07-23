package ru.tutor.codeclass.web.view;

import ru.tutor.codeclass.domain.Lesson;
import java.time.LocalDate;
import java.util.List;

public record CalendarDay(LocalDate date, boolean currentMonth, boolean today, List<Lesson> lessons) {}

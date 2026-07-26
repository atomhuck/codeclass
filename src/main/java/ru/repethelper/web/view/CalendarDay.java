package ru.repethelper.web.view;

import ru.repethelper.domain.Lesson;
import java.time.LocalDate;
import java.util.List;

public record CalendarDay(LocalDate date, boolean currentMonth, boolean today, List<Lesson> lessons) {}

package ru.tutor.codeclass.web.view;

import java.time.YearMonth;
import java.util.List;

public record CalendarView(YearMonth month, String title, int previousYear, int previousMonth, int nextYear, int nextMonth,
                           List<CalendarDay> days) {}

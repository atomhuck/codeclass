package ru.repethelper.service;

import org.springframework.stereotype.Service;
import ru.repethelper.domain.Lesson;
import ru.repethelper.web.view.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CalendarService {
    private static final Locale RU = Locale.forLanguageTag("ru-RU");
    private final ZoneId zone;
    private final Clock clock;
    public CalendarService(@org.springframework.beans.factory.annotation.Value("${app.timezone}") String timezone, Clock clock) {
        this.zone = ZoneId.of(timezone); this.clock = clock;
    }
    public CalendarView build(YearMonth month, List<Lesson> lessons) {
        Map<LocalDate, List<Lesson>> byDate = lessons.stream().collect(Collectors.groupingBy(l -> l.getStartAt().atZone(zone).toLocalDate()));
        LocalDate first = month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate today = LocalDate.now(clock.withZone(zone));
        List<CalendarDay> days = new ArrayList<>(42);
        for (int i = 0; i < 42; i++) {
            LocalDate date = first.plusDays(i);
            days.add(new CalendarDay(date, YearMonth.from(date).equals(month), date.equals(today), byDate.getOrDefault(date, List.of())));
        }
        YearMonth prev = month.minusMonths(1), next = month.plusMonths(1);
        String rawTitle = month.format(DateTimeFormatter.ofPattern("LLLL yyyy", RU));
        String title = rawTitle.substring(0, 1).toUpperCase(RU) + rawTitle.substring(1);
        return new CalendarView(month, title, prev.getYear(), prev.getMonthValue(), next.getYear(), next.getMonthValue(), days);
    }
}

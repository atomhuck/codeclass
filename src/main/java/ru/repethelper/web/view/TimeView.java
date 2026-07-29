package ru.repethelper.web.view;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.repethelper.domain.Lesson;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.text.NumberFormat;

@Component("timeView")
public class TimeView {
    private static final Locale RU = Locale.forLanguageTag("ru-RU");
    private final ZoneId zone;
    public TimeView(@Value("${app.timezone}") String timezone) { zone = ZoneId.of(timezone); }
    public String dateTime(Instant value) { return DateTimeFormatter.ofPattern("d MMMM, HH:mm", RU).format(value.atZone(zone)); }
    public String time(Instant value) { return DateTimeFormatter.ofPattern("HH:mm").format(value.atZone(zone)); }
    public String day(Instant value) { return DateTimeFormatter.ofPattern("dd").format(value.atZone(zone)); }
    public String monthShort(Instant value) { return DateTimeFormatter.ofPattern("MMM", RU).format(value.atZone(zone)); }
    public String input(Instant value) { return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").format(value.atZone(zone)); }
    public String duration(Lesson lesson) { return lesson.getDurationMinutes() + " мин"; }
    public String money(Integer rubles) {
        return rubles == null ? "Цена не указана" : NumberFormat.getIntegerInstance(RU).format(rubles) + " ₽";
    }
    public String size(long bytes) { return bytes < 1024 * 1024 ? Math.max(1, bytes / 1024) + " КБ" : String.format(RU, "%.1f МБ", bytes / 1048576.0); }
}

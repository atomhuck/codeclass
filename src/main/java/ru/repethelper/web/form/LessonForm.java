package ru.repethelper.web.form;

import jakarta.validation.constraints.*;
import org.springframework.format.annotation.DateTimeFormat;
import ru.repethelper.domain.LessonChangeScope;
import ru.repethelper.domain.LessonRecurrence;
import java.time.LocalDateTime;

public class LessonForm {
    @NotNull(message = "Выберите ученика")
    private Long studentId;
    @NotNull(message = "Укажите дату и время") @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private LocalDateTime startAt;
    @Min(value = 15, message = "Минимальная длительность — 15 минут") @Max(value = 300, message = "Максимальная длительность — 300 минут")
    private int durationMinutes = 60;
    @Min(value = 1, message = "Минимальная стоимость — 1 ₽")
    @Max(value = 1_000_000, message = "Максимальная стоимость — 1 000 000 ₽")
    private Integer priceRubles;
    @NotNull
    private LessonRecurrence recurrence = LessonRecurrence.ONCE;
    @NotNull
    private LessonChangeScope scope = LessonChangeScope.SINGLE;
    public Long getStudentId() { return studentId; }
    public void setStudentId(Long studentId) { this.studentId = studentId; }
    public LocalDateTime getStartAt() { return startAt; }
    public void setStartAt(LocalDateTime startAt) { this.startAt = startAt; }
    public int getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }
    public Integer getPriceRubles() { return priceRubles; }
    public void setPriceRubles(Integer priceRubles) { this.priceRubles = priceRubles; }
    public LessonRecurrence getRecurrence() { return recurrence; }
    public void setRecurrence(LessonRecurrence recurrence) { this.recurrence = recurrence; }
    public LessonChangeScope getScope() { return scope; }
    public void setScope(LessonChangeScope scope) { this.scope = scope; }
}

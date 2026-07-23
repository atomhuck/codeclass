package ru.tutor.codeclass.web.form;

import jakarta.validation.constraints.*;
import org.springframework.format.annotation.DateTimeFormat;
import ru.tutor.codeclass.domain.LessonChangeScope;
import ru.tutor.codeclass.domain.LessonRecurrence;
import java.time.LocalDateTime;

public class LessonForm {
    @NotNull(message = "Выберите ученика")
    private Long studentId;
    @NotNull(message = "Укажите дату и время") @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private LocalDateTime startAt;
    @Min(value = 15, message = "Минимальная длительность — 15 минут") @Max(value = 300, message = "Максимальная длительность — 300 минут")
    private int durationMinutes = 60;
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
    public LessonRecurrence getRecurrence() { return recurrence; }
    public void setRecurrence(LessonRecurrence recurrence) { this.recurrence = recurrence; }
    public LessonChangeScope getScope() { return scope; }
    public void setScope(LessonChangeScope scope) { this.scope = scope; }
}

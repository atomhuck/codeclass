package ru.repethelper.web.form;

import jakarta.validation.constraints.Size;

public class PrivateLessonNoteForm {
    @Size(max = 10000, message = "Личная заметка не должна превышать 10000 символов")
    private String note;

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}

package ru.tutor.codeclass.web.form;

import jakarta.validation.constraints.Size;

public class LessonMaterialsForm {
    @Size(max = 10000, message = "Текст домашнего задания слишком длинный")
    private String homeworkText;
    @Size(max = 10000, message = "Заметка слишком длинная")
    private String lessonNotesText;
    public String getHomeworkText() { return homeworkText; }
    public void setHomeworkText(String homeworkText) { this.homeworkText = homeworkText; }
    public String getLessonNotesText() { return lessonNotesText; }
    public void setLessonNotesText(String lessonNotesText) { this.lessonNotesText = lessonNotesText; }
}

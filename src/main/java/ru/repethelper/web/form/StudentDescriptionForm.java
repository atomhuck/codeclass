package ru.repethelper.web.form;

import jakarta.validation.constraints.Size;

public class StudentDescriptionForm {
    @Size(max = 5000, message = "Описание ученика не должно превышать 5000 символов")
    private String description;

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}

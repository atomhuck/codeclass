package ru.tutor.codeclass.web.form;

import jakarta.validation.constraints.*;

public class TeacherProfileForm {
    @NotBlank(message = "Введите имя") @Size(min = 2, max = 80, message = "Имя должно содержать от 2 до 80 символов")
    private String displayName;
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
}

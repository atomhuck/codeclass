package ru.tutor.codeclass.web.form;

import jakarta.validation.constraints.*;

public class RegistrationForm {
    @NotBlank(message = "Введите имя") @Size(min = 2, max = 80, message = "Имя должно содержать от 2 до 80 символов")
    private String displayName;
    @NotBlank(message = "Введите логин") @Pattern(regexp = "^[A-Za-z0-9._-]{3,40}$", message = "Логин: 3–40 латинских букв, цифр или символов . _ -")
    private String username;
    @NotBlank(message = "Введите пароль") @Size(min = 8, max = 72, message = "Пароль должен содержать от 8 до 72 символов")
    private String password;
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}

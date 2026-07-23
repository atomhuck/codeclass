package ru.tutor.codeclass.web.form;

import jakarta.validation.constraints.*;

public class TeacherProfileForm {
    @NotBlank(message = "Введите имя") @Size(min = 2, max = 80, message = "Имя должно содержать от 2 до 80 символов")
    private String displayName;
    @NotBlank(message = "Введите код")
    @Pattern(regexp = "^[\\p{L}\\p{N}_-]{3,30}$", message = "Код должен содержать 3–30 букв, цифр, _ или -")
    private String inviteCode;
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getInviteCode() { return inviteCode; }
    public void setInviteCode(String inviteCode) { this.inviteCode = inviteCode; }
}

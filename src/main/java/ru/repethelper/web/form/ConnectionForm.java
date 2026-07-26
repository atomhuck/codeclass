package ru.repethelper.web.form;

import jakarta.validation.constraints.*;

public class ConnectionForm {
    @NotBlank(message = "Введите код преподавателя")
    @Pattern(regexp = "^[\\p{L}\\p{N}_-]{3,30}$", message = "Код должен содержать 3–30 букв, цифр, _ или -")
    private String inviteCode;
    public String getInviteCode() { return inviteCode; }
    public void setInviteCode(String inviteCode) { this.inviteCode = inviteCode; }
}

package ru.tutor.codeclass.web.form;

import jakarta.validation.constraints.NotNull;
import ru.tutor.codeclass.domain.Role;

public class VkRegistrationForm {
    @NotNull(message = "Выберите, кем вы будете пользоваться сервисом")
    private Role role;

    public Role getRole() { return role; } public void setRole(Role v) { role = v; }
}

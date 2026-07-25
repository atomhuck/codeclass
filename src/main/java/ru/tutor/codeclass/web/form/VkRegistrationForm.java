package ru.tutor.codeclass.web.form;

import jakarta.validation.constraints.*;
import ru.tutor.codeclass.domain.Role;

public class VkRegistrationForm {
    @NotBlank @Size(min = 2, max = 80) private String displayName;
    @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{3,40}$") private String username;
    @NotBlank @Email @Size(max = 254) private String email;
    @NotNull private Role role = Role.STUDENT;
    @AssertTrue private boolean termsAccepted;
    @AssertTrue private boolean personalDataAccepted;
    public String getDisplayName() { return displayName; } public void setDisplayName(String v) { displayName = v; }
    public String getUsername() { return username; } public void setUsername(String v) { username = v; }
    public String getEmail() { return email; } public void setEmail(String v) { email = v; }
    public Role getRole() { return role; } public void setRole(Role v) { role = v; }
    public boolean isTermsAccepted() { return termsAccepted; } public void setTermsAccepted(boolean v) { termsAccepted = v; }
    public boolean isPersonalDataAccepted() { return personalDataAccepted; } public void setPersonalDataAccepted(boolean v) { personalDataAccepted = v; }
}

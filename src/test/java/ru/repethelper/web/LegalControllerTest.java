package ru.repethelper.web;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.ui.ExtendedModelMap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LegalControllerTest {
    private final LegalController controller = new LegalController(
            "Operator", "Self-employed", "123", "Krasnodar", "support@example.test");

    @Test
    void teacherReturnsFromLegalDocumentToTeacherDashboard() {
        var model = new ExtendedModelMap();
        var auth = UsernamePasswordAuthenticationToken.authenticated(
                "teacher", "", List.of(new SimpleGrantedAuthority("ROLE_TEACHER")));

        assertThat(controller.terms(auth, model)).isEqualTo("legal/terms");
        assertThat(model.get("legalHome")).isEqualTo("/teacher");
    }

    @Test
    void studentReturnsFromLegalDocumentToStudentDashboard() {
        var model = new ExtendedModelMap();
        var auth = UsernamePasswordAuthenticationToken.authenticated(
                "student", "", List.of(new SimpleGrantedAuthority("ROLE_STUDENT")));

        assertThat(controller.privacy(auth, model)).isEqualTo("legal/privacy");
        assertThat(model.get("legalHome")).isEqualTo("/student");
    }

    @Test
    void anonymousVisitorReturnsToLogin() {
        var model = new ExtendedModelMap();
        var auth = new AnonymousAuthenticationToken(
                "anonymous", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

        assertThat(controller.consent(auth, model)).isEqualTo("legal/personal-data");
        assertThat(model.get("legalHome")).isEqualTo("/login");
    }
}

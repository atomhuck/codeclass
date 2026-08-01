package ru.repethelper.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.security.core.Authentication;

@Controller
public class LegalController {
    private final String operatorName;
    private final String status;
    private final String inn;
    private final String city;
    private final String email;

    public LegalController(@Value("${app.legal.operator-name}") String operatorName,
                           @Value("${app.legal.status}") String status,
                           @Value("${app.legal.inn}") String inn,
                           @Value("${app.legal.city}") String city,
                           @Value("${app.legal.email}") String email) {
        this.operatorName = operatorName; this.status = status; this.inn = inn;
        this.city = city; this.email = email;
    }

    @GetMapping("/legal/terms") String terms(Authentication auth, Model model) { addDetails(auth, model); return "legal/terms"; }
    @GetMapping("/legal/privacy") String privacy(Authentication auth, Model model) { addDetails(auth, model); return "legal/privacy"; }
    @GetMapping("/legal/personal-data") String consent(Authentication auth, Model model) { addDetails(auth, model); return "legal/personal-data"; }

    private void addDetails(Authentication auth, Model model) {
        model.addAttribute("legalOperatorName", operatorName);
        model.addAttribute("legalStatus", status);
        model.addAttribute("legalInn", inn);
        model.addAttribute("legalCity", city);
        model.addAttribute("legalEmail", email);
        String home = "/login";
        if (auth != null && auth.isAuthenticated()) {
            boolean teacher = auth.getAuthorities().stream().anyMatch(role -> role.getAuthority().equals("ROLE_TEACHER"));
            boolean student = auth.getAuthorities().stream().anyMatch(role -> role.getAuthority().equals("ROLE_STUDENT"));
            if (teacher) home = "/teacher";
            else if (student) home = "/student";
        }
        model.addAttribute("legalHome", home);
    }
}

package ru.repethelper.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

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

    @GetMapping("/legal/terms") String terms(Model model) { addDetails(model); return "legal/terms"; }
    @GetMapping("/legal/privacy") String privacy(Model model) { addDetails(model); return "legal/privacy"; }
    @GetMapping("/legal/personal-data") String consent(Model model) { addDetails(model); return "legal/personal-data"; }

    private void addDetails(Model model) {
        model.addAttribute("legalOperatorName", operatorName);
        model.addAttribute("legalStatus", status);
        model.addAttribute("legalInn", inn);
        model.addAttribute("legalCity", city);
        model.addAttribute("legalEmail", email);
    }
}

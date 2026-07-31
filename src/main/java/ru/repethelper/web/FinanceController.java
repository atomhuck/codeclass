package ru.repethelper.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import ru.repethelper.domain.PaymentStatus;
import ru.repethelper.domain.User;
import ru.repethelper.service.*;

import java.time.DateTimeException;
import java.time.YearMonth;
import java.util.List;

@Controller
public class FinanceController {
    private final AccountService accounts;
    private final ConnectionService connections;
    private final LessonService lessons;
    private final FinanceService finances;

    public FinanceController(AccountService accounts, ConnectionService connections,
                             LessonService lessons, FinanceService finances) {
        this.accounts = accounts;
        this.connections = connections;
        this.lessons = lessons;
        this.finances = finances;
    }

    @GetMapping("/teacher/finances")
    String page(Authentication auth,
                @RequestParam(required = false) String month,
                @RequestParam(defaultValue = "0") int debtPage,
                @RequestParam(required = false) Long studentId,
                @RequestParam(defaultValue = "ALL") String period,
                HttpServletResponse response, Model model) {
        User teacher = current(auth);
        YearMonth selected = parseHtmlMonth(month);
        DebtPeriod selectedPeriod = parsePeriod(period);
        Long requestedStudentId = studentId != null && connections.studentsFor(teacher).stream()
                .anyMatch(student -> student.getId().equals(studentId)) ? studentId : null;
        var overview = finances.overview(teacher, selected, debtPage, requestedStudentId, selectedPeriod);
        response.setHeader("Cache-Control", "no-store");
        model.addAttribute("user", teacher);
        model.addAttribute("students", connections.studentsFor(teacher));
        model.addAttribute("overview", overview);
        model.addAttribute("periods", DebtPeriod.values());
        return "teacher/finances";
    }

    @GetMapping("/api/teacher/finances/months")
    @ResponseBody
    MonthsResponse months(Authentication auth, @RequestParam String end,
                          @RequestParam(defaultValue = "12") int count,
                          HttpServletResponse response) {
        if (count < 1 || count > 24) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        response.setHeader("Cache-Control", "no-store");
        List<MonthPoint> points = finances.monthSummaries(current(auth), parseApiMonth(end), count).stream()
                .map(MonthPoint::from).toList();
        return new MonthsResponse(points, !points.isEmpty() && points.getFirst().month().compareTo("2020-01") > 0);
    }

    @GetMapping("/api/teacher/finances/months/{month}/lessons")
    @ResponseBody
    MonthDetailsResponse monthLessons(Authentication auth, @PathVariable String month,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size,
                                      HttpServletResponse response) {
        if (page < 0 || size < 1 || size > 50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        response.setHeader("Cache-Control", "no-store");
        User teacher = current(auth);
        YearMonth selected = parseApiMonth(month);
        return MonthDetailsResponse.from(finances.monthSummary(teacher, selected),
                finances.monthLessons(teacher, selected, page, size));
    }

    @GetMapping("/api/teacher/finances/debts")
    @ResponseBody
    DebtResponse debts(Authentication auth,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "20") int size,
                       @RequestParam(required = false) Long studentId,
                       @RequestParam(defaultValue = "ALL") String period,
                       HttpServletResponse response) {
        if (page < 0 || size < 1 || size > 50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        response.setHeader("Cache-Control", "no-store");
        User teacher = current(auth);
        if (studentId != null && connections.studentsFor(teacher).stream()
                .noneMatch(student -> student.getId().equals(studentId)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return DebtResponse.from(finances.debts(teacher, page, size, studentId, parseApiPeriod(period)));
    }

    @PostMapping(value = "/teacher/finances/lessons/{lessonId}/payment-status", produces = MediaType.TEXT_HTML_VALUE)
    String updatePaymentHtml(Authentication auth, @PathVariable Long lessonId,
                             @RequestParam PaymentStatus status,
                             @RequestParam(required = false) Long expectedPaymentRecordId,
                             @RequestParam(required = false) String month,
                             @RequestParam(required = false) Long studentId,
                             @RequestParam(defaultValue = "ALL") String period,
                             @RequestParam(defaultValue = "0") int debtPage,
                             RedirectAttributes flash) {
        try {
            lessons.updatePaymentStatus(current(auth), lessonId, status, expectedPaymentRecordId);
            flash.addFlashAttribute("success", status == PaymentStatus.PAID
                    ? "Оплата отмечена" : "Занятие снова отмечено неоплаченным");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
        }
        return financeRedirect(month, studentId, period, debtPage);
    }

    @PostMapping(value = "/teacher/finances/lessons/{lessonId}/payment-status", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    PaymentResponse updatePaymentJson(Authentication auth, @PathVariable Long lessonId,
                                       @RequestParam PaymentStatus status,
                                       @RequestParam(required = false) Long expectedPaymentRecordId,
                                       HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        User teacher = current(auth);
        var update = lessons.updatePaymentStatus(teacher, lessonId, status, expectedPaymentRecordId);
        YearMonth month = YearMonth.from(update.lesson().getStartAt().atZone(finances.zone()));
        var summary = finances.monthSummary(teacher, month);
        var debts = finances.debts(teacher, 0, 1, null, DebtPeriod.ALL);
        return new PaymentResponse(lessonId, status, update.paymentRecordId(), MonthPoint.from(summary),
                debts.totalElements(), debts.totalAmount());
    }

    private String financeRedirect(String month, Long studentId, String period, int debtPage) {
        StringBuilder target = new StringBuilder("redirect:/teacher/finances?");
        if (month != null && !month.isBlank()) target.append("month=").append(parseHtmlMonth(month)).append('&');
        if (studentId != null) target.append("studentId=").append(studentId).append('&');
        target.append("period=").append(parsePeriod(period));
        if (debtPage > 0) target.append("&debtPage=").append(Math.max(0, debtPage));
        return target.toString();
    }

    private YearMonth parseHtmlMonth(String value) {
        try {
            YearMonth parsed = value == null || value.isBlank() ? finances.currentMonth() : YearMonth.parse(value);
            if (parsed.isAfter(finances.currentMonth()) || parsed.isBefore(YearMonth.of(2020, 1)))
                return finances.currentMonth();
            return parsed;
        } catch (DateTimeException ex) {
            return finances.currentMonth();
        }
    }

    private YearMonth parseApiMonth(String value) {
        try {
            YearMonth parsed = YearMonth.parse(value);
            if (parsed.isAfter(finances.currentMonth()) || parsed.isBefore(YearMonth.of(2020, 1)))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            return parsed;
        } catch (DateTimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }

    private DebtPeriod parsePeriod(String value) {
        try { return DebtPeriod.valueOf(value == null ? "ALL" : value); }
        catch (IllegalArgumentException ex) { return DebtPeriod.ALL; }
    }

    private DebtPeriod parseApiPeriod(String value) {
        try { return DebtPeriod.valueOf(value == null ? "ALL" : value); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
    }

    private User current(Authentication auth) { return accounts.requireByUsername(auth.getName()); }

    public record MonthPoint(String month, long expected, long received, long remaining) {
        static MonthPoint from(FinanceService.MonthSummary value) {
            return new MonthPoint(value.month().toString(), value.expected(), value.received(), value.remaining());
        }
    }

    public record MonthsResponse(List<MonthPoint> months, boolean hasMore) {}

    public record MonthDetailsResponse(MonthPoint summary, List<FinanceService.FinanceLessonRow> content,
                                       int page, int totalPages, long totalElements, boolean hasPrevious,
                                       boolean hasNext) {
        static MonthDetailsResponse from(FinanceService.MonthSummary summary,
                                         FinanceService.PageResult<FinanceService.FinanceLessonRow> page) {
            return new MonthDetailsResponse(MonthPoint.from(summary), page.content(), page.page(), page.totalPages(),
                    page.totalElements(), page.hasPrevious(), page.hasNext());
        }
    }

    public record PaymentResponse(Long lessonId, PaymentStatus status, Long paymentRecordId,
                                  MonthPoint month, long debtCount, long debtAmount) {}

    public record DebtResponse(List<FinanceService.DebtRow> content, int page, int totalPages,
                               long totalElements, long totalAmount, boolean hasPrevious, boolean hasNext) {
        static DebtResponse from(FinanceService.DebtPage page) {
            return new DebtResponse(page.content(), page.page(), page.totalPages(), page.totalElements(),
                    page.totalAmount(), page.hasPrevious(), page.hasNext());
        }
    }
}

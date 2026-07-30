package ru.repethelper.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationMailServiceTest {

    @Test
    void notificationHtmlEscapesUserContentAndKeepsHttpsLinksClickable() {
        String html = NotificationMailService.renderHtml(
                "Новое <занятие>",
                "Преподаватель <script>alert('x')</script>\nОткрыть: https://repethelper.ru/lessons/42");

        assertThat(html)
                .contains("Новое &lt;занятие&gt;")
                .contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;")
                .contains("href=\"https://repethelper.ru/lessons/42\"")
                .doesNotContain("<script>");
    }

    @Test
    void codeHtmlEscapesCodeAndUsesReadableCodeBlock() {
        String html = NotificationMailService.renderCodeHtml(
                "Подтверждение", "Введите код", "12<456", "Действует 15 минут");

        assertThat(html)
                .contains("12&lt;456")
                .contains("letter-spacing:7px")
                .doesNotContain("12<456");
    }

    @Test
    void notificationHtmlDoesNotCreateButtonForNonHttpsText() {
        String html = NotificationMailService.renderHtml("Событие", "Адрес: javascript:alert(1)");

        assertThat(html)
                .doesNotContain("<a href=")
                .contains("javascript:alert(1)");
    }
}

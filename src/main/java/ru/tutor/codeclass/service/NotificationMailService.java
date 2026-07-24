package ru.tutor.codeclass.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class NotificationMailService {
    private static final Logger log = LoggerFactory.getLogger(NotificationMailService.class);
    private final JavaMailSender sender;
    private final boolean enabled;
    private final String from;
    private final String replyTo;
    private final String baseUrl;

    public NotificationMailService(JavaMailSender sender,
            @Value("${app.mail.enabled:false}") boolean enabled,
            @Value("${app.mail.from:no-reply@repethelper.ru}") String from,
            @Value("${app.mail.reply-to:efimok05@gmail.com}") String replyTo,
            @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.sender = sender; this.enabled = enabled; this.from = from; this.replyTo = replyTo;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public void sendVerification(String email, String token) {
        String url = baseUrl + "/verify-email?token=" + token;
        send(email, "Подтвердите email в CodeClass",
                "Здравствуйте!\n\nПодтвердите email, перейдя по ссылке:\n" + url
                        + "\n\nСсылка действует 24 часа. Если это были не вы, просто проигнорируйте письмо.", url);
    }

    public void sendPasswordReset(String email, String token) {
        String url = baseUrl + "/reset-password?token=" + token;
        send(email, "Сброс пароля CodeClass",
                "Для создания нового пароля перейдите по ссылке:\n" + url
                        + "\n\nСсылка действует 30 минут и может быть использована один раз.", url);
    }

    private void send(String email, String subject, String body, String developmentUrl) {
        if (!enabled) {
            log.info("Отправка почты отключена. Ссылка для локальной разработки: {}", developmentUrl);
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setReplyTo(replyTo);
        message.setTo(email);
        message.setSubject(subject);
        message.setText(body);
        sender.send(message);
    }
}

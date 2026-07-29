package ru.repethelper.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import jakarta.mail.MessagingException;
import java.io.UnsupportedEncodingException;

@Service
public class NotificationMailService {
    private static final Logger log = LoggerFactory.getLogger(NotificationMailService.class);
    private final JavaMailSender sender;
    private final boolean enabled;
    private final String from;
    private final String replyTo;

    public NotificationMailService(JavaMailSender sender,
            @Value("${app.mail.enabled:false}") boolean enabled,
            @Value("${app.mail.from:no-reply@repethelper.ru}") String from,
            @Value("${app.mail.reply-to:efimok05@gmail.com}") String replyTo) {
        this.sender = sender; this.enabled = enabled; this.from = from; this.replyTo = replyTo;
    }

    public void sendVerification(String email, String code) {
        send(email, "Подтвердите email в RepetHelper",
                "Здравствуйте!\n\nВаш код подтверждения RepetHelper:\n\n" + code
                        + "\n\nКод действует 15 минут и может быть использован один раз."
                        + "\nЕсли это были не вы, просто проигнорируйте письмо.");
    }

    public void sendPasswordReset(String email, String code) {
        send(email, "Сброс пароля RepetHelper",
                "Ваш код для создания нового пароля:\n\n" + code
                        + "\n\nКод действует 15 минут и может быть использован один раз."
                        + "\nЕсли вы не запрашивали сброс пароля, просто проигнорируйте письмо.");
    }

    public boolean isEnabled() { return enabled; }

    public void sendNotification(String email, String subject, String body) {
        if (!enabled) return;
        try {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from, "RepetHelper");
            helper.setReplyTo(replyTo);
            helper.setTo(email);
            helper.setSubject(subject);
            helper.setText(body, false);
            sender.send(message);
        } catch (MessagingException | UnsupportedEncodingException ex) {
            throw new MailSendException("Не удалось подготовить email-уведомление", ex);
        }
    }

    private void send(String email, String subject, String body) {
        if (!enabled) {
            log.info("Отправка почты отключена; письмо не отправлено");
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

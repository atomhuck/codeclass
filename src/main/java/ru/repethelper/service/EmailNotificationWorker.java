package ru.repethelper.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.repethelper.domain.EmailNotification;
import ru.repethelper.domain.EmailNotificationStatus;
import ru.repethelper.repository.EmailNotificationRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class EmailNotificationWorker {
    private static final Logger log = LoggerFactory.getLogger(EmailNotificationWorker.class);
    private static final int MAX_ATTEMPTS = 6;
    private static final long[] RETRY_SECONDS = {60, 300, 900, 3600, 21600};
    private final EmailNotificationRepository notifications;
    private final NotificationMailService mail;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public EmailNotificationWorker(EmailNotificationRepository notifications, NotificationMailService mail,
                                   ObjectMapper objectMapper, Clock clock,
                                   PlatformTransactionManager transactionManager) {
        this.notifications = notifications;
        this.mail = mail;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${app.notifications.delivery-delay-ms:60000}",
            initialDelayString = "${app.notifications.initial-delay-ms:15000}")
    public void deliver() {
        if (!mail.isEnabled()) return;
        for (Long id : claimBatch()) deliverOne(id);
    }

    public List<Long> claimBatch() {
        List<Long> result = transactions.execute(status -> {
            Instant now = clock.instant();
            notifications.releaseStale(now.minusSeconds(900), now);
            List<EmailNotification> batch = notifications
                    .findTop25ByStatusAndAvailableAtLessThanEqualOrderByIdAsc(EmailNotificationStatus.PENDING, now);
            batch.forEach(item -> item.claim(now));
            return batch.stream().map(EmailNotification::getId).toList();
        });
        return result == null ? List.of() : result;
    }

    public void deliverOne(Long id) {
        EmailNotification item = notifications.findById(id).orElse(null);
        if (item == null || item.getStatus() != EmailNotificationStatus.PROCESSING) return;
        try {
            Map<String, String> payload = objectMapper.readValue(item.getPayload(), new TypeReference<>() {});
            mail.sendNotification(item.getRecipientEmail(), payload.get("subject"), payload.get("body"));
            markSent(id);
        } catch (Exception ex) {
            log.warn("Не удалось отправить email-уведомление {} типа {}: {}",
                    item.getId(), item.getType(), ex.getClass().getSimpleName());
            markFailed(id, ex.getMessage());
        }
    }

    public void markSent(Long id) {
        transactions.executeWithoutResult(status ->
                notifications.findById(id).ifPresent(item -> item.sent(clock.instant())));
    }

    public void markFailed(Long id, String error) {
        transactions.executeWithoutResult(status -> notifications.findById(id).ifPresent(item -> {
                int retryIndex = Math.min(item.getAttemptCount(), RETRY_SECONDS.length - 1);
                item.retryOrFail(clock.instant(), clock.instant().plusSeconds(RETRY_SECONDS[retryIndex]),
                        error, MAX_ATTEMPTS);
            }));
    }
}

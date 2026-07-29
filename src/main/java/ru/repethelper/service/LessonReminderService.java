package ru.repethelper.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.repethelper.domain.LessonStatus;
import ru.repethelper.repository.LessonRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class LessonReminderService {
    private final LessonRepository lessons;
    private final LessonService lessonService;
    private final AppNotificationService notifications;
    private final Clock clock;

    public LessonReminderService(LessonRepository lessons, LessonService lessonService,
                                 AppNotificationService notifications, Clock clock) {
        this.lessons = lessons;
        this.lessonService = lessonService;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.notifications.reminder-delay-ms:60000}",
            initialDelayString = "${app.notifications.initial-delay-ms:15000}")
    @Transactional
    public void enqueueUpcomingReminders() {
        Instant now = clock.instant();
        lessonService.materializeAllBetween(now, now.plus(Duration.ofDays(7)));
        lessons.findByStatusAndStartAtAfterAndStartAtLessThanEqualOrderByStartAtAsc(
                        LessonStatus.SCHEDULED, now, now.plus(Duration.ofHours(4)))
                .forEach(notifications::reminder);
    }
}

alter table lessons
    add column homework_submission_status varchar(20) not null default 'NOT_MARKED';

alter table lessons
    add constraint ck_lessons_homework_submission_status
        check (homework_submission_status in ('NOT_MARKED', 'SUBMITTED', 'NOT_SUBMITTED'));

drop index if exists uq_accepted_student;
drop index if exists uq_pending_connection;

create unique index uq_active_connection_pair
    on connection_requests(student_id, teacher_id)
    where status in ('PENDING', 'ACCEPTED');

create index if not exists idx_connection_student_teacher_status
    on connection_requests(student_id, teacher_id, status);

create table email_notifications (
    id bigserial primary key,
    type varchar(50) not null,
    recipient_email varchar(254) not null,
    student_id bigint references app_users(id) on delete set null,
    teacher_id bigint references app_users(id) on delete set null,
    lesson_id bigint references lessons(id) on delete set null,
    series_id uuid references lesson_series(id) on delete set null,
    payload jsonb not null,
    dedupe_key varchar(180) not null unique,
    status varchar(20) not null check (status in ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'CANCELLED')),
    available_at timestamptz not null,
    attempt_count integer not null default 0,
    created_at timestamptz not null,
    processing_started_at timestamptz,
    sent_at timestamptz,
    last_error varchar(500)
);

create index idx_email_notifications_pending
    on email_notifications(status, available_at, id);
create index idx_email_notifications_lesson
    on email_notifications(lesson_id, type);

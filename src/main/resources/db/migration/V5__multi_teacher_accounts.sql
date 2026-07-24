alter table app_users
    add column email varchar(254),
    add column email_verified_at timestamptz,
    add column auth_version bigint not null default 0,
    add column failed_login_attempts integer not null default 0,
    add column failed_login_window_started_at timestamptz,
    add column locked_until timestamptz,
    add column terms_version varchar(30),
    add column terms_accepted_at timestamptz,
    add column privacy_version varchar(30),
    add column personal_data_consent_at timestamptz;

create unique index uq_app_users_username_lower on app_users(lower(username));
create unique index uq_app_users_email_lower on app_users(lower(email)) where email is not null;
create unique index uq_teacher_invite_code_lower on teacher_profiles(lower(invite_code));

alter table lessons add column teacher_id bigint references app_users(id);
update lessons l
set teacher_id = coalesce(
    (select cr.teacher_id
     from connection_requests cr
     where cr.student_id = l.student_id and cr.status = 'ACCEPTED'
     order by cr.processed_at desc nulls last, cr.id desc
     limit 1),
    (select u.id from app_users u where u.role = 'TEACHER' order by u.id limit 1)
);
alter table lessons alter column teacher_id set not null;
create index idx_lessons_teacher_start on lessons(teacher_id, start_at);

alter table lesson_series add column teacher_id bigint references app_users(id);
update lesson_series s
set teacher_id = coalesce(
    (select cr.teacher_id
     from connection_requests cr
     where cr.student_id = s.student_id and cr.status = 'ACCEPTED'
     order by cr.processed_at desc nulls last, cr.id desc
     limit 1),
    (select u.id from app_users u where u.role = 'TEACHER' order by u.id limit 1)
);
alter table lesson_series alter column teacher_id set not null;
create index idx_lesson_series_teacher on lesson_series(teacher_id);

create table email_verification_tokens (
    id bigserial primary key,
    user_id bigint not null references app_users(id) on delete cascade,
    token_hash varchar(64) not null unique,
    expires_at timestamptz not null,
    used_at timestamptz,
    created_at timestamptz not null
);
create index idx_email_verification_user on email_verification_tokens(user_id);

create table password_reset_tokens (
    id bigserial primary key,
    user_id bigint not null references app_users(id) on delete cascade,
    token_hash varchar(64) not null unique,
    expires_at timestamptz not null,
    used_at timestamptz,
    created_at timestamptz not null
);
create index idx_password_reset_user on password_reset_tokens(user_id);

create table app_users (
    id bigserial primary key,
    username varchar(40) not null unique,
    password_hash varchar(100) not null,
    display_name varchar(80) not null,
    role varchar(20) not null check (role in ('TEACHER', 'STUDENT')),
    enabled boolean not null default true,
    created_at timestamptz not null
);

create table teacher_profiles (
    id bigserial primary key,
    user_id bigint not null unique references app_users(id),
    invite_code varchar(30) not null unique
);

create table connection_requests (
    id bigserial primary key,
    student_id bigint not null references app_users(id),
    teacher_id bigint not null references app_users(id),
    status varchar(20) not null check (status in ('PENDING', 'ACCEPTED', 'REJECTED')),
    created_at timestamptz not null,
    processed_at timestamptz
);
create index idx_connection_teacher_status on connection_requests(teacher_id, status);
create index idx_connection_student_status on connection_requests(student_id, status);
create unique index uq_pending_connection on connection_requests(student_id, teacher_id) where status = 'PENDING';
create unique index uq_accepted_student on connection_requests(student_id) where status = 'ACCEPTED';

create table lessons (
    id bigserial primary key,
    student_id bigint not null references app_users(id),
    start_at timestamptz not null,
    duration_minutes integer not null check (duration_minutes between 15 and 300),
    status varchar(20) not null check (status in ('SCHEDULED', 'CANCELLED')),
    homework_text text,
    lesson_notes_text text,
    created_at timestamptz not null,
    updated_at timestamptz not null
);
create index idx_lessons_student_start on lessons(student_id, start_at);
create index idx_lessons_start on lessons(start_at);

create table attachments (
    id bigserial primary key,
    lesson_id bigint not null references lessons(id) on delete cascade,
    category varchar(30) not null check (category in ('HOMEWORK', 'LESSON_NOTES')),
    original_name varchar(255) not null,
    stored_name varchar(80) not null unique,
    content_type varchar(150) not null,
    size_bytes bigint not null,
    created_at timestamptz not null
);
create index idx_attachments_lesson_category on attachments(lesson_id, category);

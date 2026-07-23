create table lesson_series (
    id uuid primary key,
    student_id bigint not null references app_users(id),
    anchor_start_at timestamptz not null,
    duration_minutes integer not null check (duration_minutes between 15 and 300),
    cancelled_from_index integer,
    created_at timestamptz not null,
    updated_at timestamptz not null
);
create index idx_lesson_series_student on lesson_series(student_id);

alter table lessons add column series_id uuid references lesson_series(id);
alter table lessons add column occurrence_index integer;
alter table lessons add constraint chk_lesson_series_occurrence
    check ((series_id is null and occurrence_index is null) or
           (series_id is not null and occurrence_index is not null and occurrence_index >= 0));
create unique index uq_lesson_series_occurrence on lessons(series_id, occurrence_index)
    where series_id is not null;

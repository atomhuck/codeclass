create table lesson_payment_records (
    id bigserial primary key,
    teacher_id bigint not null references app_users(id) on delete cascade,
    lesson_id bigint references lessons(id) on delete set null,
    amount_rubles integer not null check (amount_rubles between 1 and 1000000),
    lesson_start_at timestamptz not null,
    recorded_at timestamptz not null
);

create unique index uq_lesson_payment_records_lesson
    on lesson_payment_records(lesson_id)
    where lesson_id is not null;

create index idx_lesson_payment_records_teacher_start
    on lesson_payment_records(teacher_id, lesson_start_at);

create index idx_lessons_teacher_unpaid_start
    on lessons(teacher_id, start_at, id)
    where payment_status = 'UNPAID' and price_rubles is not null;

insert into lesson_payment_records(teacher_id, lesson_id, amount_rubles, lesson_start_at, recorded_at)
select teacher_id, id, price_rubles, start_at, updated_at
from lessons
where payment_status = 'PAID' and price_rubles is not null;

create or replace function sync_lesson_payment_record()
returns trigger
language plpgsql
as $$
begin
    if new.payment_status = 'PAID' and new.price_rubles is not null then
        insert into lesson_payment_records(teacher_id, lesson_id, amount_rubles, lesson_start_at, recorded_at)
        values (new.teacher_id, new.id, new.price_rubles, new.start_at, current_timestamp)
        on conflict (lesson_id) where lesson_id is not null
        do update set
            teacher_id = excluded.teacher_id,
            amount_rubles = excluded.amount_rubles,
            lesson_start_at = excluded.lesson_start_at;
    else
        delete from lesson_payment_records where lesson_id = new.id;
    end if;
    return new;
end;
$$;

create trigger trg_lessons_sync_payment_record
after insert or update of payment_status, price_rubles, start_at, teacher_id
on lessons
for each row execute function sync_lesson_payment_record();

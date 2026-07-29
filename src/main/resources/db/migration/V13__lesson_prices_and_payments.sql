alter table lessons
    add column price_rubles integer,
    add column payment_status varchar(20) not null default 'NO_PRICE';

alter table lessons
    add constraint ck_lessons_price_rubles
        check (price_rubles is null or price_rubles between 1 and 1000000),
    add constraint ck_lessons_payment_status
        check (payment_status in ('NO_PRICE', 'UNPAID', 'PAID')),
    add constraint ck_lessons_price_payment_consistency
        check ((price_rubles is null and payment_status = 'NO_PRICE')
            or (price_rubles is not null and payment_status in ('UNPAID', 'PAID')));

alter table lesson_series
    add column base_price_rubles integer;

alter table lesson_series
    add constraint ck_lesson_series_base_price
        check (base_price_rubles is null or base_price_rubles between 1 and 1000000);

create table lesson_series_price_changes (
    id uuid primary key,
    series_id uuid not null references lesson_series(id) on delete cascade,
    effective_occurrence_index integer not null check (effective_occurrence_index >= 0),
    price_rubles integer check (price_rubles is null or price_rubles between 1 and 1000000),
    created_at timestamptz not null,
    constraint uq_series_price_change_index unique (series_id, effective_occurrence_index)
);

create index idx_series_price_changes_lookup
    on lesson_series_price_changes(series_id, effective_occurrence_index);

create table lesson_series_exclusions (
    series_id uuid not null references lesson_series(id) on delete cascade,
    occurrence_index integer not null check (occurrence_index >= 0),
    primary key (series_id, occurrence_index)
);

insert into lesson_series_exclusions(series_id, occurrence_index)
select series_id, occurrence_index
from lessons
where status = 'CANCELLED' and series_id is not null
on conflict do nothing;

delete from lessons where status = 'CANCELLED';

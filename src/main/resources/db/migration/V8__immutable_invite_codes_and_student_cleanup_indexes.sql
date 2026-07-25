create or replace function prevent_teacher_invite_code_change()
returns trigger
language plpgsql
as $$
begin
    if new.invite_code is distinct from old.invite_code then
        raise exception 'Invite code cannot be changed';
    end if;
    return new;
end;
$$;

create trigger trg_teacher_profiles_invite_code_immutable
before update of invite_code on teacher_profiles
for each row execute function prevent_teacher_invite_code_change();

create index idx_connection_student_teacher on connection_requests(student_id, teacher_id);
create index idx_lessons_teacher_student on lessons(teacher_id, student_id);
create index idx_lesson_series_teacher_student on lesson_series(teacher_id, student_id);

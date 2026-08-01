-- Fast lookup for the paginated board history inside a teacher–student pair.
create index if not exists idx_lessons_teacher_student_start_desc
    on lessons(teacher_id, student_id, start_at desc, id desc);

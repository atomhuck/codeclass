alter table lessons
    add column teacher_private_note text;

alter table lessons
    add constraint ck_lessons_teacher_private_note_length
        check (teacher_private_note is null or char_length(teacher_private_note) <= 10000);

alter table connection_requests
    add column teacher_student_description text;

alter table connection_requests
    add constraint ck_connection_teacher_student_description_length
        check (teacher_student_description is null or char_length(teacher_student_description) <= 5000);

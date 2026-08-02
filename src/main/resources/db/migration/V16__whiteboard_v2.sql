alter table whiteboards
    add column custom_name varchar(120);

alter table whiteboard_objects
    drop constraint if exists whiteboard_objects_object_type_check;

alter table whiteboard_objects
    add constraint whiteboard_objects_object_type_check
        check (object_type in ('PATH', 'IMAGE', 'TEXT')),
    add column deleted_at timestamptz,
    add column deleted_by bigint references app_users(id) on delete set null,
    add column delete_operation_id uuid;

drop index if exists idx_whiteboard_objects_board_order;
create index idx_whiteboard_objects_board_order_active
    on whiteboard_objects(board_id, z_order)
    where deleted_at is null;
create index idx_whiteboard_objects_deleted_at
    on whiteboard_objects(deleted_at)
    where deleted_at is not null;
create index idx_whiteboards_lesson
    on whiteboards(lesson_id);

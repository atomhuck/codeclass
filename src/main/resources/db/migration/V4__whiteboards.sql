create table whiteboards (
    id bigserial primary key,
    lesson_id bigint not null unique references lessons(id) on delete cascade,
    public_id uuid not null unique,
    revision bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create table whiteboard_objects (
    id uuid primary key,
    board_id bigint not null references whiteboards(id) on delete cascade,
    object_type varchar(20) not null check (object_type in ('PATH', 'IMAGE')),
    object_data jsonb not null,
    z_order bigint not null,
    object_version bigint not null default 0,
    created_by bigint not null references app_users(id),
    created_at timestamptz not null,
    updated_at timestamptz not null
);
create index idx_whiteboard_objects_board_order on whiteboard_objects(board_id, z_order);

create table whiteboard_images (
    object_id uuid primary key references whiteboard_objects(id) on delete cascade,
    original_name varchar(255) not null,
    stored_name varchar(80) not null unique,
    content_type varchar(80) not null,
    size_bytes bigint not null,
    width integer not null,
    height integer not null
);

alter table app_users alter column password_hash drop not null;

create table external_identities (
    id bigserial primary key,
    user_id bigint not null references app_users(id) on delete cascade,
    provider varchar(20) not null,
    provider_subject varchar(120) not null,
    email_at_link varchar(254),
    created_at timestamptz not null,
    last_login_at timestamptz,
    constraint uq_external_identity_provider_subject unique (provider, provider_subject),
    constraint uq_external_identity_user_provider unique (user_id, provider)
);

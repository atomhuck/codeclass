alter table email_verification_tokens
    alter column token_hash type varchar(100),
    add column failed_attempts integer not null default 0;

alter table password_reset_tokens
    alter column token_hash type varchar(100),
    add column failed_attempts integer not null default 0;

create table if not exists users (
    id bigint primary key auto_increment,
    username varchar(255) not null unique,
    password varchar(255) not null,
    role varchar(32) not null,
    created_at timestamp not null
);

create table if not exists refresh_tokens (
    id bigint primary key auto_increment,
    token_hash varchar(100) not null unique,
    user_id bigint not null,
    expires_at timestamp not null,
    revoked boolean not null,
    created_at timestamp not null,
    constraint fk_refresh_tokens_user foreign key (user_id) references users(id)
);

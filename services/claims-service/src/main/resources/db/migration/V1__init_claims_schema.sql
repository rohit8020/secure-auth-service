create table if not exists claims (
    id varchar(64) primary key,
    policy_id varchar(64) not null,
    policyholder_id bigint not null,
    assigned_agent_id bigint not null,
    description text not null,
    claim_amount numeric(12, 2) not null,
    status varchar(32) not null,
    decision_notes text,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index if not exists idx_claims_policy_id on claims(policy_id);
create index if not exists idx_claims_policyholder_id on claims(policyholder_id);
create index if not exists idx_claims_assigned_agent_id on claims(assigned_agent_id);
create index if not exists idx_claims_status on claims(status);
create index if not exists idx_claims_created_at on claims(created_at);

create table if not exists policy_projections (
    id varchar(64) primary key,
    policyholder_id bigint not null,
    assigned_agent_id bigint not null,
    status varchar(32) not null,
    claimable boolean not null,
    updated_at timestamp with time zone not null
);

create table if not exists outbox_events (
    id varchar(64) primary key,
    aggregate_type varchar(32) not null,
    aggregate_id varchar(64) not null,
    event_type varchar(64) not null,
    event_version integer not null,
    payload text not null,
    status varchar(32) not null,
    retry_count integer not null,
    available_at timestamp with time zone not null,
    created_at timestamp with time zone not null,
    sent_at timestamp with time zone,
    last_error text
);

create index if not exists idx_claims_outbox_status_available_at
    on outbox_events(status, available_at);
create index if not exists idx_claims_outbox_aggregate_id
    on outbox_events(aggregate_id);

create table if not exists idempotency_keys (
    id bigserial primary key,
    operation varchar(64) not null,
    actor_id bigint not null,
    idempotency_key varchar(255) not null,
    request_hash varchar(64) not null,
    response_status integer not null,
    response_body text not null,
    resource_id varchar(64) not null,
    expires_at timestamp with time zone not null,
    created_at timestamp with time zone not null,
    constraint uk_claims_idempotency_scope unique (operation, actor_id, idempotency_key)
);

create index if not exists idx_claims_idempotency_expires_at
    on idempotency_keys(expires_at);

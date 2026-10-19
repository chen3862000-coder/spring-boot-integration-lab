create table if not exists fulfillment_requests (
    id bigint auto_increment primary key,
    idempotency_key varchar(200) not null,
    request_hash varchar(64) not null,
    request_id varchar(80) not null,
    order_id varchar(100) not null,
    sku varchar(100) not null,
    quantity integer not null,
    status varchar(30) not null,
    partner_request_id varchar(100),
    partner_reference varchar(100),
    response_body text,
    message text,
    created_at timestamp(6) not null default current_timestamp(6),
    updated_at timestamp(6) not null default current_timestamp(6) on update current_timestamp(6),
    constraint uq_fulfillment_idempotency unique (idempotency_key),
    constraint uq_fulfillment_request_id unique (request_id)
);

create index if not exists idx_fulfillment_status on fulfillment_requests(status);

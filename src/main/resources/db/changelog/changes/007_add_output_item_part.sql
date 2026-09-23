create table if not exists output_item_part (
    id bigserial not null,
    output_item_id bigint not null,
    part_type varchar(20) not null,
    part_index bigint not null,
    sequence bigint not null,
    text text not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (id),
    constraint fk_output_item_part_output_item foreign key (output_item_id) references output_item (id)
);

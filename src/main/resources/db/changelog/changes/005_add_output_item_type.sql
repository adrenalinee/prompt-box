alter table output_item
    add column if not exists item_type varchar(20) not null default 'UNKNOWN';

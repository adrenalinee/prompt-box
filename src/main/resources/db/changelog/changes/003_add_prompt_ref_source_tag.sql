-- Add source_tag_ref_id to prompt_ref (for refs created from TAG)
alter table prompt_ref
    add column if not exists source_tag_ref_id bigint;

alter table prompt_ref
    add constraint fk_prompt_ref_source_tag
        foreign key (source_tag_ref_id) references prompt_ref (id);

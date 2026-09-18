-- Hibernate creates local/test tables first. Scalar references still need physical foreign keys.
alter table orders add constraint fk_orders_user foreign key (user_id) references users (id) on delete restrict;
alter table order_line_item add constraint fk_order_line_item_product foreign key (product_id) references product (id) on delete restrict;

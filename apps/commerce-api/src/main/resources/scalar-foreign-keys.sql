-- Hibernate creates local/test tables first. Scalar references still need physical foreign keys.
alter table orders add constraint FK_ORDERS_USER foreign key (user_id) references users (id) on delete restrict;
alter table order_line_item add constraint FK_ORDER_LINE_ITEM_PRODUCT foreign key (product_id) references product (id) on delete restrict;
alter table likes add constraint FK_LIKES_USER foreign key (user_id) references users (id) on delete restrict;
alter table likes add constraint FK_LIKES_PRODUCT foreign key (product_id) references product (id) on delete restrict;
alter table point_account add constraint FK_POINT_ACCOUNT_USER foreign key (user_id) references users (id) on delete restrict;

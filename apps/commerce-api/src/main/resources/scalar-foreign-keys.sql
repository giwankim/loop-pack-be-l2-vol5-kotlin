-- Hibernate creates local/test tables first. Scalar references still need physical foreign keys.
alter table orders add constraint FK_ORDERS_ON_USER_ID foreign key (user_id) references users (id) on delete restrict;
alter table order_line_item add constraint FK_ORDER_LINE_ITEM_ON_PRODUCT_ID foreign key (product_id) references product (id) on delete restrict;
alter table likes add constraint FK_LIKES_ON_USER_ID foreign key (user_id) references users (id) on delete restrict;
alter table likes add constraint FK_LIKES_ON_PRODUCT_ID foreign key (product_id) references product (id) on delete restrict;
alter table point_account add constraint FK_POINT_ACCOUNT_ON_USER_ID foreign key (user_id) references users (id) on delete restrict;
alter table payment add constraint FK_PAYMENT_ON_ORDER_ID foreign key (order_id) references orders (id) on delete restrict;

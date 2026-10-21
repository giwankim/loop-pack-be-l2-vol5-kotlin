-- Runs before Hibernate drops the local/test tables. Hibernate drops only the foreign keys it maps, then the tables in name order.
-- A scalar foreign key from a table that sorts after the table it references would block that table's drop (MySQL error 3730),
-- so such keys are dropped here first. On an empty schema the table is missing and Hibernate logs a warning and goes on.
alter table payment drop foreign key FK_PAYMENT_ON_ORDER_ID;

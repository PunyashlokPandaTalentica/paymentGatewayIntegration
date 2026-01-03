-- Alter currency columns from CHAR(3) to VARCHAR(3) to match Hibernate entity definitions
ALTER TABLE orders ALTER COLUMN currency TYPE VARCHAR(3);
ALTER TABLE payments ALTER COLUMN currency TYPE VARCHAR(3);
ALTER TABLE payment_transactions ALTER COLUMN currency TYPE VARCHAR(3);


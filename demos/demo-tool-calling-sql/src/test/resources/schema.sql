CREATE TABLE demo_orders (
    owner_principal_id VARCHAR(128) NOT NULL,
    order_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    total_amount DECIMAL(12, 2) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

INSERT INTO demo_orders VALUES
    ('principal-a', 'order-a', 'PAID', 12.50, TIMESTAMP WITH TIME ZONE '2026-08-21 10:00:00+00:00'),
    ('principal-b', 'order-b', 'PAID', 88.00, TIMESTAMP WITH TIME ZONE '2026-08-21 11:00:00+00:00');

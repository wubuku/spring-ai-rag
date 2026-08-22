CREATE TABLE demo_inventory (
    owner_principal_id VARCHAR(128) NOT NULL,
    sku VARCHAR(64) NOT NULL,
    warehouse_code VARCHAR(64) NOT NULL,
    available_quantity INTEGER NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

INSERT INTO demo_inventory VALUES
    ('principal-a', 'SKU-1', 'WH-1', 12,
     TIMESTAMP WITH TIME ZONE '2026-08-21 10:00:00+00:00'),
    ('principal-a', 'SKU-2', 'WH-2', 8,
     TIMESTAMP WITH TIME ZONE '2026-08-21 09:00:00+00:00'),
    ('principal-b', 'SKU-1', 'WH-1', 88,
     TIMESTAMP WITH TIME ZONE '2026-08-21 11:00:00+00:00');

-- Account contact details are entered in the ERP, not seeded on deployment.
BEGIN;

CREATE TABLE IF NOT EXISTS staff_contact (
    username varchar(64) PRIMARY KEY,
    phone varchar(50)
);

COMMIT;

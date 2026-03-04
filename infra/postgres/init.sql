-- PostgreSQL initialization script
-- user_db is created automatically via POSTGRES_DB env var.
-- Create additional databases for other services.

CREATE DATABASE order_db;
CREATE DATABASE inventory_db;

-- Grant all privileges to root user
GRANT ALL PRIVILEGES ON DATABASE order_db TO root;
GRANT ALL PRIVILEGES ON DATABASE inventory_db TO root;

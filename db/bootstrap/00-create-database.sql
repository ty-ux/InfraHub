-- InfraHub database bootstrap
-- Run this script while connected to the default `postgres` database
-- or another admin database.
--
-- IMPORTANT:
-- CREATE DATABASE cannot run inside a transaction block.
-- If your SQL client auto-wraps scripts in BEGIN/COMMIT, do not run this file there.
-- Use psql/createdb, or execute just the CREATE DATABASE statement manually.

-- Recommended manual command:
--   psql -U postgres -d postgres -f 00-create-database.sql

CREATE DATABASE infrahub
    WITH
    ENCODING = 'UTF8'
    TEMPLATE = template0;

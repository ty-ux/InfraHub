-- InfraHub post database bootstrap
-- Run this after the `infrahub` database has been created.
--
-- Example:
--   psql -U postgres -d postgres -f 00-post-create-database.sql

COMMENT ON DATABASE infrahub IS 'InfraHub technical platform database';

-- Optional follow-up, adjust per environment:
-- ALTER DATABASE infrahub OWNER TO your_app_user;
-- GRANT ALL PRIVILEGES ON DATABASE infrahub TO your_app_user;

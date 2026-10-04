-- Execute with psql connected to an administrative database, not inside a transaction.
-- Example: psql -h localhost -U postgres -d postgres -v db_owner=lishou_agent -f database/00-create-database.sql
-- db_owner must be an existing PostgreSQL role. This script never creates users/passwords.
\set ON_ERROR_STOP on
\if :{?db_owner}
\else
\echo 'Missing variable db_owner: pass -v db_owner=<existing-role>'
\quit 1
\endif

-- CREATE DATABASE cannot execute inside Flyway's migration transaction.
-- Re-running leaves an existing database and its owner/data unchanged.
SELECT format('CREATE DATABASE %I OWNER %I ENCODING %L TEMPLATE template0',
              'lishou_agent', :'db_owner', 'UTF8')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'lishou_agent')
\gexec

\connect lishou_agent

-- The PostgreSQL server must have the pgvector extension installed first.
-- Administrator pre-installation allows subsequent Flyway DDL to use a database-owner role.
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;

-- Table DDL has one source of truth:
-- src/main/resources/db/migration/V1__initial_schema.sql
-- Start the application against this empty database to let Flyway apply V1.

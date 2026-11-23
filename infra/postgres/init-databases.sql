-- Creates one database per service (database-per-service pattern).
--
-- Run this ONCE against your local Postgres. In pgAdmin: right-click your
-- server > Query Tool, paste this, and run. (Or: psql -U postgres -f this-file.)
--
-- Note: CREATE DATABASE cannot run inside a transaction block, so run the
-- statements as-is (pgAdmin's Query Tool handles this fine).

CREATE DATABASE authdb;
CREATE DATABASE userdb;
CREATE DATABASE restaurantdb;
CREATE DATABASE orderdb;
CREATE DATABASE paymentdb;
CREATE DATABASE deliverydb;

-- Tables are created automatically by each service on startup
-- (Hibernate ddl-auto=update), so nothing else is needed here.

-- AEGER - rôles, bases et tables (idempotent : peut être relancé)
-- Un rôle par service, avec le minimum de droits (mots de passe venant de Vault).

-- ---------------------------------------------------------------- rôles
SELECT format('CREATE ROLE %I LOGIN', r) FROM unnest(ARRAY['vitals_writer','vitals_reader','kpi_writer','grafana_ro','keycloak']) r
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) \gexec
ALTER ROLE vitals_writer PASSWORD :'vitals_writer_pw';
ALTER ROLE vitals_reader PASSWORD :'vitals_reader_pw';
ALTER ROLE kpi_writer    PASSWORD :'kpi_writer_pw';
ALTER ROLE grafana_ro    PASSWORD :'grafana_ro_pw';
ALTER ROLE keycloak      PASSWORD :'keycloak_pw';

-- ---------------------------------------------------------------- bases
SELECT 'CREATE DATABASE vitals'   WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'vitals') \gexec
SELECT 'CREATE DATABASE kpi'      WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'kpi') \gexec
SELECT 'CREATE DATABASE keycloak OWNER keycloak' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'keycloak') \gexec
REVOKE ALL ON DATABASE vitals, kpi, keycloak FROM PUBLIC;
GRANT CONNECT ON DATABASE vitals TO vitals_writer, vitals_reader;
GRANT CONNECT ON DATABASE kpi TO kpi_writer, grafana_ro;
GRANT CONNECT ON DATABASE keycloak TO keycloak;

-- ---------------------------------------------------------------- base vitals : historique 7 jours
\connect vitals
CREATE EXTENSION IF NOT EXISTS timescaledb;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE TABLE IF NOT EXISTS public.measures (
  time        TIMESTAMPTZ NOT NULL,
  patient_id  TEXT NOT NULL,
  bracelet_id TEXT NOT NULL,
  type        TEXT NOT NULL,
  seq         BIGINT NOT NULL,
  heart_rate  INT,
  spo2        INT,
  temperature REAL,
  systolic    INT,
  diastolic   INT,
  anomaly     BOOLEAN NOT NULL DEFAULT false,
  UNIQUE (bracelet_id, seq, time)
);
SELECT create_hypertable('public.measures', by_range('time', INTERVAL '1 day'), if_not_exists => TRUE);
CREATE INDEX IF NOT EXISTS measures_patient_time ON public.measures (patient_id, time DESC);
-- après 7 jours, les mesures sont supprimées automatiquement (l'archive reste dans le Data Lake)
SELECT add_retention_policy('public.measures', INTERVAL '7 days', if_not_exists => TRUE);
GRANT USAGE ON SCHEMA public TO vitals_writer, vitals_reader;
GRANT INSERT ON public.measures TO vitals_writer;      -- vitals_writer : INSERT seul
GRANT SELECT ON public.measures TO vitals_reader;      -- vitals_reader : SELECT seul

-- ---------------------------------------------------------------- base kpi : indicateurs + catalogue Iceberg
\connect kpi
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA IF NOT EXISTS kpi;
CREATE SCHEMA IF NOT EXISTS lake AUTHORIZATION kpi_writer;   -- catalogue Iceberg (JDBC) géré par Spark
CREATE TABLE IF NOT EXISTS kpi.daily_summary (
  day DATE PRIMARY KEY, nb_measures BIGINT, nb_patients INT, nb_alerts BIGINT,
  nb_high_risk_measures BIGINT, nb_standard_measures BIGINT, nb_anomalies BIGINT,
  avg_heart_rate DOUBLE PRECISION, avg_spo2 DOUBLE PRECISION, avg_temperature DOUBLE PRECISION,
  pct_alert DOUBLE PRECISION, computed_at TIMESTAMPTZ);
CREATE TABLE IF NOT EXISTS kpi.daily_by_type (
  day DATE, type TEXT, nb_measures BIGINT, nb_patients INT, nb_alerts BIGINT,
  avg_heart_rate DOUBLE PRECISION, avg_spo2 DOUBLE PRECISION, avg_temperature DOUBLE PRECISION,
  PRIMARY KEY (day, type));
CREATE TABLE IF NOT EXISTS kpi.hourly (
  day DATE, hour INT, nb_measures BIGINT, nb_alerts BIGINT, avg_heart_rate DOUBLE PRECISION,
  PRIMARY KEY (day, hour));
CREATE TABLE IF NOT EXISTS kpi.top_patients (
  day DATE, patient_id TEXT, type TEXT, nb_alerts BIGINT, min_spo2 INT, max_heart_rate INT,
  PRIMARY KEY (day, patient_id));
GRANT USAGE ON SCHEMA kpi TO kpi_writer, grafana_ro;
GRANT SELECT, INSERT, DELETE ON ALL TABLES IN SCHEMA kpi TO kpi_writer;   -- remplace les lignes du jour
GRANT SELECT ON ALL TABLES IN SCHEMA kpi TO grafana_ro;                   -- grafana_ro : SELECT seul

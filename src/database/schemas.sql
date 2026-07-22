-- ============================================================================
-- DATABASE SCHEMAS — PostgreSQL + TimescaleDB
-- Tables: users, market_data (hypertable), trades, positions, journal,
-- templates, settings, alerts, watchlists, drawings, sessions
-- ============================================================================

-- Enable TimescaleDB extension
CREATE EXTENSION IF NOT EXISTS timescaledb;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ============================================================================
-- USERS & AUTH
-- ============================================================================

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) UNIQUE NOT NULL,
    username VARCHAR(100) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) DEFAULT 'trader' CHECK (role IN ('admin', 'trader', 'viewer')),
    pin_hash VARCHAR(255),
    biometric_credential_id TEXT,
    account_balance DECIMAL(18,2) DEFAULT 100000.00,
    account_currency VARCHAR(3) DEFAULT 'USD',
    is_active BOOLEAN DEFAULT true,
    last_login TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL,
    device_id VARCHAR(255),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ============================================================================
-- MARKET DATA (TimescaleDB hypertable for time-series)
-- ============================================================================

CREATE TABLE market_data (
    time TIMESTAMPTZ NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    timeframe VARCHAR(5) NOT NULL,
    open DECIMAL(18,8) NOT NULL,
    high DECIMAL(18,8) NOT NULL,
    low DECIMAL(18,8) NOT NULL,
    close DECIMAL(18,8) NOT NULL,
    volume DECIMAL(18,4) DEFAULT 0,
    tick_volume INTEGER DEFAULT 0,
    provider VARCHAR(30) DEFAULT 'DUKASCOPY'
);

-- Convert to TimescaleDB hypertable (partitioned by time)
SELECT create_hypertable('market_data', 'time',
    chunk_time_interval => INTERVAL '1 day');

-- Composite index for fast queries
CREATE INDEX idx_market_data_symbol_tf_time
    ON market_data (symbol, timeframe, time DESC);

-- ============================================================================
-- TICK DATA (separate hypertable — high volume)
-- ============================================================================

CREATE TABLE tick_data (
    time TIMESTAMPTZ NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    bid DECIMAL(18,8) NOT NULL,
    ask DECIMAL(18,8) NOT NULL,
    bid_volume DECIMAL(18,4),
    ask_volume DECIMAL(18,4)
);

SELECT create_hypertable('tick_data', 'time',
    chunk_time_interval => INTERVAL '1 hour');

CREATE INDEX idx_tick_symbol_time ON tick_data (symbol, time DESC);


-- ============================================================================
-- TRADES & POSITIONS
-- ============================================================================

CREATE TABLE trades (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    symbol VARCHAR(20) NOT NULL,
    timeframe VARCHAR(5),
    direction VARCHAR(7) NOT NULL CHECK (direction IN ('BULLISH', 'BEARISH')),
    side VARCHAR(4) NOT NULL CHECK (side IN ('BUY', 'SELL')),
    entry_price DECIMAL(18,8) NOT NULL,
    exit_price DECIMAL(18,8),
    stop_loss DECIMAL(18,8),
    take_profit_1 DECIMAL(18,8),
    take_profit_2 DECIMAL(18,8),
    take_profit_3 DECIMAL(18,8),
    volume DECIMAL(18,6) NOT NULL,
    commission DECIMAL(18,4) DEFAULT 0,
    swap DECIMAL(18,4) DEFAULT 0,
    gross_pnl DECIMAL(18,4),
    net_pnl DECIMAL(18,4),
    r_multiple DECIMAL(8,3),
    risk_reward DECIMAL(8,3),
    status VARCHAR(20) DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED', 'PARTIAL')),
    setup_type VARCHAR(50),
    confidence INTEGER,
    broker VARCHAR(30),
    broker_order_id VARCHAR(100),
    opened_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_trades_user_status ON trades (user_id, status);
CREATE INDEX idx_trades_user_symbol ON trades (user_id, symbol);
CREATE INDEX idx_trades_opened ON trades (opened_at DESC);

CREATE TABLE positions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    symbol VARCHAR(20) NOT NULL,
    side VARCHAR(4) NOT NULL,
    volume DECIMAL(18,6) NOT NULL,
    avg_entry_price DECIMAL(18,8) NOT NULL,
    current_price DECIMAL(18,8),
    unrealized_pnl DECIMAL(18,4),
    realized_pnl DECIMAL(18,4) DEFAULT 0,
    trailing_stop_enabled BOOLEAN DEFAULT false,
    trailing_stop_distance DECIMAL(18,8),
    break_even_enabled BOOLEAN DEFAULT false,
    break_even_triggered BOOLEAN DEFAULT false,
    broker VARCHAR(30),
    status VARCHAR(20) DEFAULT 'OPEN',
    opened_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- ============================================================================
-- TRADE JOURNAL
-- ============================================================================

CREATE TABLE journal_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    trade_id UUID REFERENCES trades(id) ON DELETE SET NULL,
    symbol VARCHAR(20) NOT NULL,
    timeframe VARCHAR(5),
    direction VARCHAR(7),
    result VARCHAR(10) CHECK (result IN ('WIN', 'LOSS', 'BREAKEVEN', 'OPEN')),
    entry_price DECIMAL(18,8),
    exit_price DECIMAL(18,8),
    stop_loss DECIMAL(18,8),
    pnl DECIMAL(18,4),
    pnl_percent DECIMAL(8,4),
    r_multiple DECIMAL(8,3),
    setup_type VARCHAR(50),
    confidence INTEGER,
    confluence_factors TEXT[],
    notes TEXT,
    emotion VARCHAR(20),
    emotion_before VARCHAR(20),
    emotion_after VARCHAR(20),
    mistakes TEXT[],
    followed_plan BOOLEAN DEFAULT true,
    screenshot_url TEXT,
    chart_state JSONB,
    indicators JSONB,
    tags TEXT[],
    entry_time TIMESTAMPTZ,
    exit_time TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_journal_user ON journal_entries (user_id, created_at DESC);
CREATE INDEX idx_journal_result ON journal_entries (user_id, result);

-- ============================================================================
-- TEMPLATES & SETTINGS
-- ============================================================================

CREATE TABLE templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(30) NOT NULL,
    timeframe VARCHAR(5),
    config JSONB NOT NULL,
    is_default BOOLEAN DEFAULT false,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE user_settings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE UNIQUE,
    theme VARCHAR(50) DEFAULT 'midnight',
    active_workspace JSONB,
    risk_config JSONB,
    alert_config JSONB,
    voice_config JSONB,
    shortcuts JSONB,
    gestures JSONB,
    sync_enabled BOOLEAN DEFAULT true,
    auto_analyze BOOLEAN DEFAULT true,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- ============================================================================
-- WATCHLISTS & ALERTS
-- ============================================================================

CREATE TABLE watchlists (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    symbols JSONB NOT NULL, -- Array of {symbol, assetClass, enabled}
    is_default BOOLEAN DEFAULT false,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE alerts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(200) NOT NULL,
    body TEXT,
    priority VARCHAR(10) DEFAULT 'MEDIUM',
    symbol VARCHAR(20),
    channels TEXT[],
    acknowledged BOOLEAN DEFAULT false,
    data JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_alerts_user ON alerts (user_id, created_at DESC);

-- ============================================================================
-- DRAWINGS (user chart annotations persisted)
-- ============================================================================

CREATE TABLE drawings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    symbol VARCHAR(20) NOT NULL,
    timeframe VARCHAR(5),
    type VARCHAR(30) NOT NULL,
    config JSONB NOT NULL, -- Full drawing serialization
    auto_generated BOOLEAN DEFAULT false,
    visible BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- ============================================================================
-- AUDIT LOG
-- ============================================================================

CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    action VARCHAR(50) NOT NULL,
    entity_type VARCHAR(30),
    entity_id UUID,
    details JSONB,
    ip_address INET,
    user_agent TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_audit_user ON audit_log (user_id, created_at DESC);

-- ============================================================================
-- DATA RETENTION POLICIES (TimescaleDB)
-- ============================================================================

-- Auto-drop tick data older than 90 days
SELECT add_retention_policy('tick_data', INTERVAL '90 days');

-- Keep candle data indefinitely (or set long retention)
-- SELECT add_retention_policy('market_data', INTERVAL '10 years');

-- Continuous aggregate for faster daily queries
CREATE MATERIALIZED VIEW market_data_daily
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 day', time) AS bucket,
    symbol,
    first(open, time) AS open,
    max(high) AS high,
    min(low) AS low,
    last(close, time) AS close,
    sum(volume) AS volume
FROM market_data
WHERE timeframe = 'M1'
GROUP BY bucket, symbol;

-- Refresh policy
SELECT add_continuous_aggregate_policy('market_data_daily',
    start_offset => INTERVAL '3 days',
    end_offset => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour');

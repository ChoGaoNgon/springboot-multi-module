CREATE TABLE step (
    step_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    step_count INT,
    sync_time TIMESTAMP NOT NULL,
    device_id VARCHAR(255) NOT NULL,
    kcal INT,
    distance INT,
    total_time BIGINT,
    step_type SMALLINT DEFAULT 1 NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    del_flag SMALLINT DEFAULT 0,
    PRIMARY KEY (step_id)
);

CREATE TABLE daily_point (
    daily_point_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    step_count INT,
    sync_date VARCHAR(10) NOT NULL,
    device_id VARCHAR(255) NOT NULL,
    kcal INT,
    distance INT,
    total_time BIGINT,
    earn_point INT,
    step_event INT,
    point_event SMALLINT,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    del_flag SMALLINT DEFAULT 0,
    PRIMARY KEY (daily_point_id)
);

CREATE TABLE monthly_point (
    monthly_point_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    step_count INT,
    month VARCHAR(6) NOT NULL,
    kcal INT,
    distance INT,
    total_time BIGINT,
    earn_point INT,
    used_point INT,
    revocation_point INT,
    rest_point INT,
    step_event INT,
    point_event SMALLINT,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    del_flag SMALLINT DEFAULT 0,
    PRIMARY KEY (monthly_point_id)
);

CREATE TABLE change_point_history (
    change_point_history_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    amount_point INT NOT NULL,
    action_type SMALLINT NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    del_flag SMALLINT DEFAULT 0,
    PRIMARY KEY (change_point_history_id)
);

CREATE TABLE transaction_point (
    transaction_point_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    transaction_type SMALLINT NOT NULL,
    amount_point INT,
    transaction_status SMALLINT NOT NULL,
    transaction_time TIMESTAMP NOT NULL,
    device_id VARCHAR(255) NOT NULL,
    system_os VARCHAR(255) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    del_flag SMALLINT DEFAULT 0,
    PRIMARY KEY (transaction_point_id)
);

CREATE TABLE transaction_point_history (
    transaction_point_history_id BIGINT NOT NULL AUTO_INCREMENT,
    transaction_point_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    transaction_time TIMESTAMP NOT NULL,
    transaction_type SMALLINT NOT NULL,
    amount_point INT,
    transaction_status SMALLINT NOT NULL,
    device_id VARCHAR(255) NOT NULL,
    total_point INT,
    rest_point INT,
    message TEXT,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    del_flag SMALLINT DEFAULT 0,
    PRIMARY KEY (transaction_point_history_id)
);

CREATE TABLE user (
    user_id BIGINT NOT NULL AUTO_INCREMENT,
    user_name VARCHAR(255) NOT NULL,
    contract_no VARCHAR(255) NOT NULL,
    contract_status SMALLINT NOT NULL,
    contract_term SMALLINT NOT NULL,
    phone_number VARCHAR(20) NOT NULL,
    email VARCHAR(255) NOT NULL,
    contract_time_start TIMESTAMP,
    contract_time_end TIMESTAMP,
    invitation_code VARCHAR(20),
    group_number SMALLINT,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    del_flag SMALLINT DEFAULT 0,
    PRIMARY KEY (user_id)
);

INSERT INTO monthly_point
  (user_id, step_count, month, kcal, distance, total_time, earn_point, used_point, revocation_point, rest_point, step_event, point_event, created_by, created_at, updated_by, updated_at, del_flag)
VALUES
  (1, 1000, '202212', 0, 0, 0, 100, 30, 10, 0, 0, 50, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0),
  (1, 2000, '202212', 0, 0, 0, 200,  5,  2, 0, 0, 20, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0),
  (1, 9999, '202212', 0, 0, 0, 999, 99, 99, 0, 0, 99, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 1),
  (1, 3000, '202211', 0, 0, 0, 777, 77, 77, 0, 0, 77, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0);

INSERT INTO user
  (user_name, contract_no, contract_status, contract_term, phone_number, email, invitation_code, group_number, created_by, created_at, updated_by, updated_at, del_flag)
VALUES
  ('Test Taro', 'C-0001', 1, 1, '080111123456', 'taro@example.com', 'INV001', 3, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0);

-- Seed for the daily-point endpoint, which also exercises the DATE() SQL on transaction_point.
-- transaction_type 1 = PAYPAY, 3 = REVOCATION; transaction_status 1 = SUCCESS.
INSERT INTO daily_point
  (user_id, step_count, sync_date, device_id, kcal, distance, total_time, earn_point, step_event, point_event, created_by, created_at, updated_by, updated_at, del_flag)
VALUES
  (1, 500, '2022-12-01', 'dev-1', 0, 0, 0, 40, 0, 10, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0);

INSERT INTO transaction_point
  (user_id, transaction_type, amount_point, transaction_status, transaction_time, device_id, system_os, created_by, created_at, updated_by, updated_at, del_flag)
VALUES
  (1, 1, 15, 1, TIMESTAMP '2022-11-30 10:00:00', 'dev-1', 'iOS', 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0),
  (1, 3,  5, 1, TIMESTAMP '2022-11-29 10:00:00', 'dev-1', 'iOS', 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0);

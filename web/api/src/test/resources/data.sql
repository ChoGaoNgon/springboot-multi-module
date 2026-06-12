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

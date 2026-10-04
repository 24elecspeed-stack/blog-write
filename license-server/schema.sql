-- phpMyAdmin(또는 호스팅의 DB 관리 도구)에서 이 SQL을 한 번 실행해서 테이블을 만든다.
CREATE TABLE IF NOT EXISTS license_keys (
    id INT AUTO_INCREMENT PRIMARY KEY,
    license_key VARCHAR(64) NOT NULL UNIQUE,
    device_id VARCHAR(128) NULL,           -- 아직 아무 기기에서도 안 썼으면 NULL
    note VARCHAR(255) NULL,                -- 누구에게 줬는지 메모(선택)
    activated_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

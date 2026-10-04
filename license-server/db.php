<?php
// 라이선스 서버 공용 DB 연결 파일. cPanel(또는 호스팅 관리자 화면)에서 새 MySQL 데이터베이스와
// 사용자를 하나 만든 뒤, 아래 네 값만 채워 넣으면 된다. 워드프레스가 쓰는 DB를 그대로 써도 되고
// (워드프레스 테이블과는 이름이 겹치지 않으니 안전하다), 완전히 새 DB를 만들어도 된다.

define('LICENSE_DB_HOST', 'localhost');
define('LICENSE_DB_NAME', '여기에_DB이름');
define('LICENSE_DB_USER', '여기에_DB사용자');
define('LICENSE_DB_PASS', '여기에_DB비밀번호');

// 키 생성(generate.php) 접근을 막는 관리자 비밀번호 - 반드시 추측하기 어려운 값으로 바꿔서 쓴다.
define('LICENSE_ADMIN_SECRET', '여기에_직접_정한_관리자_비밀번호');

function license_db(): PDO {
    static $pdo = null;
    if ($pdo === null) {
        $dsn = 'mysql:host=' . LICENSE_DB_HOST . ';dbname=' . LICENSE_DB_NAME . ';charset=utf8mb4';
        $pdo = new PDO($dsn, LICENSE_DB_USER, LICENSE_DB_PASS, [
            PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        ]);
    }
    return $pdo;
}

function license_json($data, int $status = 200): void {
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($data, JSON_UNESCAPED_UNICODE);
    exit;
}

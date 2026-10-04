<?php
// 관리자 전용 키 생성 화면. 브라우저에서 이렇게 접속해서 쓴다:
//   https://24elecspeed.com/license/generate.php?admin=db.php에_적은_관리자_비밀번호&count=1&note=홍길동
// count(기본 1)만큼 옛날 시리얼 번호 느낌의 키(예: A7K9M-3PQXZ-8DFRT-2WNBV)를 만들어 DB에 등록하고
// 화면에 보여준다. note는 "누구에게 준 키인지" 나중에 알아보기 위한 메모(선택, 생략 가능).
require __DIR__ . '/db.php';

if (($_GET['admin'] ?? '') !== LICENSE_ADMIN_SECRET) {
    http_response_code(403);
    echo '접근 권한이 없습니다.';
    exit;
}

$count = max(1, min(20, (int) ($_GET['count'] ?? 1)));
$note = trim($_GET['note'] ?? '');

// 0,O,1,I,L처럼 눈으로 헷갈리는 글자는 빼서, 사람이 손으로 옮겨 적어도 실수가 적게 만든다.
$alphabet = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';

function license_generate_key(string $alphabet): string {
    $groups = [];
    for ($g = 0; $g < 4; $g++) {
        $chars = '';
        for ($i = 0; $i < 5; $i++) {
            $chars .= $alphabet[random_int(0, strlen($alphabet) - 1)];
        }
        $groups[] = $chars;
    }
    return implode('-', $groups);
}

$pdo = license_db();
$insert = $pdo->prepare('INSERT INTO license_keys (license_key, note) VALUES (?, ?)');
$created = [];

for ($i = 0; $i < $count; $i++) {
    // 아주 드물게 같은 키가 이미 있을 수 있으니(중복 UNIQUE 제약), 겹치면 다시 만든다.
    while (true) {
        $key = license_generate_key($alphabet);
        try {
            $insert->execute([$key, $note !== '' ? $note : null]);
            $created[] = $key;
            break;
        } catch (PDOException $e) {
            if ($e->getCode() !== '23000') throw $e; // 중복 키(23000) 외의 오류는 그대로 알린다.
        }
    }
}

header('Content-Type: text/plain; charset=utf-8');
echo implode("\n", $created) . "\n";

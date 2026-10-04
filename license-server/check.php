<?php
// 앱이 실행될 때마다(또는 주기적으로) 부르는 재확인 API - 키를 온라인에서 지우거나(revoke),
// 다른 기기로 device_id를 바꿔치기하면 여기서 실패가 돌아온다.
// POST key, device_id
require __DIR__ . '/db.php';

$key = trim($_POST['key'] ?? '');
$deviceId = trim($_POST['device_id'] ?? '');

if ($key === '' || $deviceId === '') {
    license_json(['ok' => false, 'error' => '키와 기기 정보가 필요합니다.'], 400);
}

$pdo = license_db();
$stmt = $pdo->prepare('SELECT device_id FROM license_keys WHERE license_key = ?');
$stmt->execute([$key]);
$row = $stmt->fetch();

// 키 자체가 없다 = 관리자가 목록에서 지웠다(회수) -> 실패.
if (!$row) {
    license_json(['ok' => false, 'error' => '키가 취소되었습니다.']);
}

// 이 기기로 등록된 적이 없는데 check가 불렸다는 건 비정상 상태 - 안전하게 실패 처리.
if ($row['device_id'] !== $deviceId) {
    license_json(['ok' => false, 'error' => '다른 기기에 등록된 키입니다.']);
}

license_json(['ok' => true]);

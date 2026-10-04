<?php
// 앱이 최초 설치·키 입력 시 호출하는 활성화 API.
// POST key, device_id
// - 키가 존재하지 않으면: 실패("존재하지 않는 키")
// - 키가 있고 device_id가 비어있으면(아직 아무 기기도 안 씀): 이 기기로 등록하고 성공
// - 키가 있고 device_id가 이미 지금 보낸 값과 같으면(같은 기기 재설치 등): 성공
// - 키가 있고 device_id가 다른 값으로 이미 채워져 있으면: 실패("다른 기기에서 이미 사용 중")
require __DIR__ . '/db.php';

$key = trim($_POST['key'] ?? '');
$deviceId = trim($_POST['device_id'] ?? '');

if ($key === '' || $deviceId === '') {
    license_json(['ok' => false, 'error' => '키와 기기 정보가 필요합니다.'], 400);
}

$pdo = license_db();
$stmt = $pdo->prepare('SELECT id, device_id FROM license_keys WHERE license_key = ?');
$stmt->execute([$key]);
$row = $stmt->fetch();

if (!$row) {
    license_json(['ok' => false, 'error' => '존재하지 않는 키입니다.']);
}

if ($row['device_id'] === null || $row['device_id'] === '') {
    $update = $pdo->prepare('UPDATE license_keys SET device_id = ?, activated_at = NOW() WHERE id = ?');
    $update->execute([$deviceId, $row['id']]);
    license_json(['ok' => true]);
}

if ($row['device_id'] === $deviceId) {
    license_json(['ok' => true]);
}

license_json(['ok' => false, 'error' => '이미 다른 기기에서 사용 중인 키입니다.']);

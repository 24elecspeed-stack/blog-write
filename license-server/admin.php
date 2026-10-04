<?php
// 라이선스 키 관리용 관리자 페이지. 브라우저(폰이든 PC든)에서 접속해서 쓴다:
//   https://24elecspeed.com/license/admin.php
// db.php에 적어둔 LICENSE_ADMIN_SECRET으로 로그인한다. 키 생성, 목록 확인, 회수(삭제)를
// 이 한 페이지에서 전부 할 수 있다 - 별도 앱 설치 없이 브라우저만 있으면 된다.
require __DIR__ . '/db.php';
session_start();

$error = '';

if (isset($_POST['login'])) {
    if (($_POST['password'] ?? '') === LICENSE_ADMIN_SECRET) {
        $_SESSION['license_admin'] = true;
    } else {
        $error = '비밀번호가 틀렸습니다.';
    }
}
if (isset($_GET['logout'])) {
    session_destroy();
    header('Location: admin.php');
    exit;
}

$loggedIn = $_SESSION['license_admin'] ?? false;

if ($loggedIn) {
    $pdo = license_db();

    // 새 키 생성
    if (isset($_POST['generate'])) {
        $count = max(1, min(20, (int) ($_POST['count'] ?? 1)));
        $note = trim($_POST['note'] ?? '');
        $alphabet = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'; // 0,O,1,I,L처럼 헷갈리는 글자는 제외
        $insert = $pdo->prepare('INSERT INTO license_keys (license_key, note) VALUES (?, ?)');
        for ($i = 0; $i < $count; $i++) {
            while (true) {
                $groups = [];
                for ($g = 0; $g < 4; $g++) {
                    $chars = '';
                    for ($j = 0; $j < 5; $j++) $chars .= $alphabet[random_int(0, strlen($alphabet) - 1)];
                    $groups[] = $chars;
                }
                $key = implode('-', $groups);
                try {
                    $insert->execute([$key, $note !== '' ? $note : null]);
                    break;
                } catch (PDOException $e) {
                    if ($e->getCode() !== '23000') throw $e;
                }
            }
        }
        header('Location: admin.php');
        exit;
    }

    // 키 회수(삭제) - 삭제된 키는 앱의 다음 확인(check.php) 때 바로 잠긴다.
    if (isset($_POST['revoke'])) {
        $id = (int) $_POST['revoke'];
        $pdo->prepare('DELETE FROM license_keys WHERE id = ?')->execute([$id]);
        header('Location: admin.php');
        exit;
    }

    // 기기 등록 해제(다른 기기에서 다시 쓸 수 있게 device_id만 비움, 키 자체는 유지)
    if (isset($_POST['unbind'])) {
        $id = (int) $_POST['unbind'];
        $pdo->prepare('UPDATE license_keys SET device_id = NULL, activated_at = NULL WHERE id = ?')->execute([$id]);
        header('Location: admin.php');
        exit;
    }

    $keys = $pdo->query('SELECT * FROM license_keys ORDER BY created_at DESC')->fetchAll();
}
?>
<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>키워드 리서치 라이선스 관리</title>
<style>
  body { font-family: -apple-system, sans-serif; background: #F5F7FB; color: #132238; margin: 0; padding: 16px; }
  h1 { font-size: 18px; }
  .card { background: #fff; border: 1px solid #DDE4EE; border-radius: 12px; padding: 16px; margin-bottom: 16px; }
  input[type=text], input[type=password], input[type=number] { width: 100%; box-sizing: border-box; padding: 10px; border: 1px solid #DDE4EE; border-radius: 8px; margin-bottom: 8px; font-size: 14px; }
  button { background: #173B70; color: #fff; border: none; border-radius: 8px; padding: 10px 16px; font-size: 14px; }
  table { width: 100%; border-collapse: collapse; font-size: 13px; }
  th, td { border-bottom: 1px solid #DDE4EE; padding: 8px; text-align: left; word-break: break-all; }
  .used { color: #C0392B; }
  .free { color: #2E7D32; }
  form.inline { display: inline; }
  .error { color: #C0392B; }
</style>
</head>
<body>
<h1>키워드 리서치 라이선스 관리</h1>
<?php if (!$loggedIn): ?>
  <div class="card">
    <?php if ($error): ?><p class="error"><?= htmlspecialchars($error) ?></p><?php endif; ?>
    <form method="post">
      <input type="password" name="password" placeholder="관리자 비밀번호">
      <button type="submit" name="login" value="1">로그인</button>
    </form>
  </div>
<?php else: ?>
  <p><a href="?logout=1">로그아웃</a></p>
  <div class="card">
    <h3>새 키 만들기</h3>
    <form method="post">
      <input type="number" name="count" value="1" min="1" max="20" placeholder="개수">
      <input type="text" name="note" placeholder="메모 (예: 누구에게 줄 키인지)">
      <button type="submit" name="generate" value="1">키 생성</button>
    </form>
  </div>
  <div class="card">
    <h3>발급된 키 목록 (<?= count($keys) ?>개)</h3>
    <table>
      <tr><th>키</th><th>메모</th><th>상태</th><th>등록일</th><th>관리</th></tr>
      <?php foreach ($keys as $k): ?>
      <tr>
        <td><?= htmlspecialchars($k['license_key']) ?></td>
        <td><?= htmlspecialchars($k['note'] ?? '') ?></td>
        <td>
          <?php if ($k['device_id']): ?>
            <span class="used">사용 중</span>
          <?php else: ?>
            <span class="free">미사용</span>
          <?php endif; ?>
        </td>
        <td><?= htmlspecialchars($k['activated_at'] ?? '-') ?></td>
        <td>
          <?php if ($k['device_id']): ?>
          <form class="inline" method="post" onsubmit="return confirm('이 키의 기기 등록을 해제할까요? 다른 폰에서 다시 쓸 수 있게 됩니다.');">
            <input type="hidden" name="unbind" value="<?= (int)$k['id'] ?>">
            <button type="submit">기기 해제</button>
          </form>
          <?php endif; ?>
          <form class="inline" method="post" onsubmit="return confirm('이 키를 완전히 회수(삭제)할까요? 그 즉시 사용 중인 앱도 잠깁니다.');">
            <input type="hidden" name="revoke" value="<?= (int)$k['id'] ?>">
            <button type="submit">회수</button>
          </form>
        </td>
      </tr>
      <?php endforeach; ?>
    </table>
  </div>
<?php endif; ?>
</body>
</html>

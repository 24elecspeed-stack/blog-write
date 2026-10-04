param([string]$Serial = 'emulator-5582', [switch]$GalleryUri)
$ErrorActionPreference = 'Stop'
if ($Serial -notlike 'emulator-*') { throw 'This helper only targets an emulator.' }
$env:ANDROID_USER_HOME = Join-Path $env:USERPROFILE '.android'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
function Read-Ui {
    Start-Sleep -Seconds 1
    & $adb -s $Serial shell uiautomator dump /sdcard/heading-test-ui.xml | Out-Null
    [xml]$doc = (& $adb -s $Serial shell cat /sdcard/heading-test-ui.xml)
    return $doc.SelectNodes('//node')
}
function Tap-Node($node) {
    if (!$node) { throw 'Required UI control was not found.' }
    $coords = [regex]::Matches($node.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    & $adb -s $Serial shell input tap ([int](($coords[0]+$coords[2])/2)) ([int](($coords[1]+$coords[3])/2))
    Start-Sleep -Seconds 2
}
$nodes = Read-Ui
$title = $nodes | Where-Object { $_.'resource-id' -eq 'com.nhn.android.blog:id/document_title' } | Select-Object -First 1
if ($title) {
    if ($title.text -notlike 'TEST-01*' -and $title.text -ne 'Add a title') { throw 'Refusing to close a non-test draft.' }
    Tap-Node ($nodes | Where-Object { $_.text -eq '취소' } | Select-Object -First 1)
    $nodes = Read-Ui
    $discard = $nodes | Where-Object { $_.'content-desc' -eq '작성취소' } | Select-Object -First 1
    if ($discard) { Tap-Node $discard }
}
$nodes = Read-Ui
Tap-Node ($nodes | Where-Object { $_.'content-desc' -like '글쓰기, 탭*' } | Select-Object -First 1)
$nodes = Read-Ui
Tap-Node ($nodes | Where-Object { $_.'content-desc' -eq '블로그 글쓰기' } | Select-Object -First 1)
$nodes = Read-Ui
if (!($nodes | Where-Object { $_.'resource-id' -eq 'com.nhn.android.blog:id/document_title' })) { throw 'Empty editor not ready.' }
$apk = Join-Path $PSScriptRoot '..\app\build\outputs\apk\headingTest\app-headingTest.apk'
& $adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'Install failed.' }
$component = 'com.keyword.research.headingtest/com.keyword.research.HeadingTestService'
$enabled = (& $adb -s $Serial shell settings get secure enabled_accessibility_services).Trim()
$others = @($enabled.Split(':') | Where-Object { $_ -and $_ -ne 'null' -and $_ -ne $component })
$without = if ($others.Count) { $others -join ':' } else { 'null' }
& $adb -s $Serial shell settings put secure enabled_accessibility_services $without
& $adb -s $Serial shell settings put secure enabled_accessibility_services (($others + $component) -join ':')
& $adb -s $Serial shell settings put secure accessibility_enabled 1
& $adb -s $Serial shell am start -n com.keyword.research.headingtest/com.keyword.research.FullDraftTest --ez gallery_uri ($GalleryUri.IsPresent.ToString().ToLowerInvariant())
$nodes = Read-Ui
Tap-Node ($nodes | Where-Object { $_.text -eq '전체 샘플 시작' } | Select-Object -First 1)
Write-Output 'Full draft test started. Inspect HeadingTest logcat and the Naver editor. Never publish.'

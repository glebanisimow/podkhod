# Emulator UI helpers for manual verification (not used by the build).
# Usage: . .\tools\ui.ps1; Ui-Texts; Ui-Tap "Начать тренировку"
$script:adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Ui-TapId([string]$tag, [int]$index = 0) {
    $nodes = @((Ui-Dump).SelectNodes("//node[@resource-id='$tag']"))
    if ($nodes.Count -le $index) { throw "not found: $tag" }
    $b = $nodes[$index].bounds -replace '[\[\]]', ' ' -split '[ ,]+' | Where-Object { $_ -ne '' }
    & $script:adb shell input tap (([int]$b[0] + [int]$b[2]) / 2) (([int]$b[1] + [int]$b[3]) / 2)
}

function Ui-Ids {
    (Ui-Dump).SelectNodes("//node[@resource-id!='']") | ForEach-Object { "{0} '{1}' {2}" -f $_.'resource-id', $_.text, $_.bounds }
}

function Ui-Shot([string]$name) {
    & $script:adb shell screencap -p /sdcard/shot.png
    & $script:adb pull /sdcard/shot.png $name | Out-Null
}

function Ui-Dump {
    & $script:adb shell uiautomator dump /sdcard/ui.xml | Out-Null
    & $script:adb pull /sdcard/ui.xml "$env:TEMP\ui.xml" | Out-Null
    [xml](Get-Content "$env:TEMP\ui.xml" -Encoding UTF8)
}

function Ui-Texts {
    (Ui-Dump).SelectNodes("//node[@text!='' or @content-desc!='']") | ForEach-Object {
        "{0}{1} {2}" -f $_.text, $(if ($_.'content-desc') { " [" + $_.'content-desc' + "]" } else { "" }), $_.bounds
    }
}

function Ui-Tap([string]$text, [int]$index = 0) {
    $nodes = @((Ui-Dump).SelectNodes("//node[@text='$text' or @content-desc='$text']"))
    if ($nodes.Count -le $index) { throw "not found: $text" }
    $b = $nodes[$index].bounds -replace '[\[\]]', ' ' -split '[ ,]+' | Where-Object { $_ -ne '' }
    $x = ([int]$b[0] + [int]$b[2]) / 2
    $y = ([int]$b[1] + [int]$b[3]) / 2
    & $script:adb shell input tap $x $y
}

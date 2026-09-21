param(
    [string]$OutputDir = "serve-8000"
)

$ErrorActionPreference = "Stop"

$RootDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$WrapperJar = Join-Path $RootDir "gradle\wrapper\gradle-wrapper.jar"
$OutputPath = Join-Path $RootDir $OutputDir

if (-not (Test-Path -LiteralPath $WrapperJar)) {
    throw "Gradle wrapper jar not found: $WrapperJar"
}

Push-Location $RootDir
try {
    & java "-Dorg.gradle.appname=gradlew" -classpath $WrapperJar org.gradle.wrapper.GradleWrapperMain assembleDebug
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    $Apk = Get-ChildItem -Path "app\build\outputs\apk" -Recurse -Filter "*.apk" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if (-not $Apk) {
        throw "No APK found under app\build\outputs\apk"
    }

    New-Item -ItemType Directory -Force -Path $OutputPath | Out-Null
    Copy-Item -LiteralPath $Apk.FullName -Destination (Join-Path $OutputPath $Apk.Name) -Force
    Copy-Item -LiteralPath $Apk.FullName -Destination (Join-Path $OutputPath "latest.apk") -Force

    Write-Output "APK: $($Apk.FullName)"
    Write-Output "Mounted: $(Join-Path $OutputPath 'latest.apk')"
} finally {
    Pop-Location
}

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$JdkBin = "C:\Program Files\Java\jdk-22\bin"
if (-not (Test-Path (Join-Path $JdkBin "javac.exe"))) {
    $JavacCmd = Get-Command javac -ErrorAction Stop
    $JdkBin = Split-Path -Parent $JavacCmd.Source
}

$Javac = Join-Path $JdkBin "javac.exe"
$JarTool = Join-Path $JdkBin "jar.exe"
$Java = Join-Path $JdkBin "java.exe"

$LibJar = Join-Path $Root "lib\montoya-api.jar"
$BuildMain = Join-Path $Root "build\classes\main"
$BuildTest = Join-Path $Root "build\classes\test"
$DistDir = Join-Path $Root "dist"
$OutJar = Join-Path $DistDir "theMythOfCrawl-crawler.jar"

if (-not (Test-Path $LibJar)) {
    throw "Missing Montoya API JAR at $LibJar"
}

New-Item -ItemType Directory -Force -Path $BuildMain, $BuildTest, $DistDir | Out-Null
Remove-Item -Recurse -Force "$BuildMain\*" -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force "$BuildTest\*" -ErrorAction SilentlyContinue

$MainSources = @(Get-ChildItem -Path (Join-Path $Root "src\main\java") -Recurse -Filter "*.java" | ForEach-Object { $_.FullName })
Write-Host "Compiling $($MainSources.Count) main Java files (--release 17)..."
& $Javac --release 17 -encoding UTF-8 -Xlint:unchecked,deprecation -cp $LibJar -d $BuildMain @MainSources
if ($LASTEXITCODE -ne 0) {
    throw "javac main compilation failed with exit code $LASTEXITCODE"
}

$ResDir = Join-Path $Root "src\main\resources"
if (Test-Path $ResDir) {
    Copy-Item -Path "$ResDir\*" -Destination $BuildMain -Recurse -Force
}

$TestSources = @(Get-ChildItem -Path (Join-Path $Root "src\test\java") -Recurse -Filter "*.java" | ForEach-Object { $_.FullName })
if ($TestSources.Count -gt 0) {
    $TestCp = $BuildMain + ";" + $LibJar
    Write-Host "Compiling $($TestSources.Count) test Java files..."
    & $Javac --release 17 -encoding UTF-8 -Xlint:unchecked,deprecation -cp $TestCp -d $BuildTest @TestSources
    if ($LASTEXITCODE -ne 0) {
        throw "javac test compilation failed with exit code $LASTEXITCODE"
    }

    $RunCp = $BuildTest + ";" + $BuildMain + ";" + $LibJar
    Push-Location $Root
    try {
        Write-Host "Running CrawlerUnitTests..."
        & $Java "-Djava.awt.headless=true" -cp $RunCp com.mythofcrawl.crawler.CrawlerUnitTests
        if ($LASTEXITCODE -ne 0) {
            throw "CrawlerUnitTests failed with exit code $LASTEXITCODE"
        }

        Write-Host "Running DeepCrawlerTests..."
        & $Java "-Djava.awt.headless=true" -cp $RunCp com.mythofcrawl.crawler.DeepCrawlerTests
        if ($LASTEXITCODE -ne 0) {
            throw "DeepCrawlerTests failed with exit code $LASTEXITCODE"
        }

        Write-Host "Running BlueprintGuardTests..."
        & $Java "-Djava.awt.headless=true" -cp $RunCp com.mythofcrawl.crawler.BlueprintGuardTests
        if ($LASTEXITCODE -ne 0) {
            throw "BlueprintGuardTests failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

Write-Host "Packaging JAR: $OutJar..."
& $JarTool --create --file $OutJar -C $BuildMain .
if ($LASTEXITCODE -ne 0) {
    throw "jar packaging failed with exit code $LASTEXITCODE"
}

Write-Host "SUCCESS! Extension JAR ready at: $OutJar"

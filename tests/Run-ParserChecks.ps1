param([string]$DevJdkRoot = $env:JAVA_HOME, [string]$Fb2Sample = '', [ValidatePattern('^[0-9]+[mMgG]$')][string]$DevMaxHeap = '128m')
$ErrorActionPreference = 'Stop'
if (-not $DevJdkRoot) { throw 'Pass -DevJdkRoot with the path to JDK 17 or newer.' }
$devReader = Split-Path -Parent $PSScriptRoot
$devOutput = Join-Path ([System.IO.Path]::GetTempPath()) ('reader-parser-checks-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $devOutput | Out-Null
$devSources = @(
    (Join-Path $PSScriptRoot 'support\android\text\Html.java'),
    (Join-Path $PSScriptRoot 'support\android\text\TextUtils.java'),
    (Join-Path $PSScriptRoot 'support\ru\grant\reader\BookRepository.java'),
    (Join-Path $devReader 'app\src\main\java\ru\grant\reader\BookTextParser.java'),
    (Join-Path $devReader 'app\src\main\java\ru\grant\reader\ReadingPosition.java'),
    (Join-Path $PSScriptRoot 'ParserRegression.java'),
    (Join-Path $PSScriptRoot 'StreamingFb2Regression.java'),
    (Join-Path $PSScriptRoot 'BookMemoryProbe.java')
)
& (Join-Path $DevJdkRoot 'bin\javac.exe') -encoding UTF-8 -d $devOutput @devSources
if ($LASTEXITCODE -ne 0) { throw 'Parser-check compilation failed.' }
$devArguments = @("-Xmx$DevMaxHeap", '-cp', $devOutput, 'ru.grant.reader.ParserRegression')
if ($Fb2Sample) { $devArguments += $Fb2Sample }
& (Join-Path $DevJdkRoot 'bin\java.exe') @devArguments
if ($LASTEXITCODE -ne 0) { throw 'Parser regression failed.' }
& (Join-Path $DevJdkRoot 'bin\java.exe') "-Xmx$DevMaxHeap" -cp $devOutput ru.grant.reader.StreamingFb2Regression
if ($LASTEXITCODE -ne 0) { throw 'Streaming FB2 regression failed.' }

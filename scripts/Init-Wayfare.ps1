#Requires -Version 5.1
<#
.SYNOPSIS
    把「毕设」摄影平台项目复制一份并重命名为 Wayfare（行走集）新项目骨架。

.DESCRIPTION
    只读源目录 —— 所有写操作都发生在目标目录内，源目录一个字节都不会被改动
    （脚本结束前会做文件数校验并打印结果）。

    会复制：
      · 后端 Java 源码 79 个 .java  → wayfare-backend（包名 com.photoshare → com.wayfare）
      · 前端 src 全部文件（32 个）+ 4 个构建配置 → wayfare-frontend
      · schema.sql → db/schema.sql（建库名 photo_share → wayfare，表名不动）
      · uploads 测试图 → wayfare-backend/uploads
      · 三份 Wayfare 文档 → docs/
      · 参考资料（需求文档 / 技术文档 / 接口测试截图 / 旧 application.yml）→ docs/refs/
      · .gitignore（重写）

    有意不复制：target/、.idea/、node_modules/、运行日志、旧版提示词与文档。
    pom.xml 与 application.yml 由 P0-A 任务块重写，本脚本不生成。

.PARAMETER Source
    源目录，默认取脚本所在目录。

.PARAMETER Destination
    目标目录，默认取「源目录的同级 Wayfare 目录」。

.PARAMETER Force
    目标目录已存在且非空时，先把它改名备份为 Wayfare.__backup_<时间戳> 再重建。
    不加此开关会直接报错退出（不会覆盖任何东西）。
    **注意：-Force 不删除任何文件** —— 旧目录只是被改名，确认新目录没问题后自行删除备份即可。

.PARAMETER SkipRebrand
    跳过界面文案改写（光影集 → 行走集 等）。

.PARAMETER DryRun
    只打印计划，不写任何文件。建议第一次先跑这个。

.EXAMPLE
    .\Init-Wayfare.ps1 -DryRun
    先看计划，确认无误。

.EXAMPLE
    .\Init-Wayfare.ps1
    正式执行，生成 ..\Wayfare 骨架。

.NOTES
    仅支持 Windows。需要 PowerShell 5.1 或更高。

    【改这个文件之前必读】
    本文件必须保存为 UTF-8 **带 BOM**。Windows PowerShell 5.1 读无 BOM 的 .ps1 会按
    ANSI 解码，脚本里的中文会全部变成乱码（不报错，只是输出难看、替换失配）。
    用编辑器改完若发现中文变乱，执行一次：
      $p='路径\Init-Wayfare.ps1'
      $t=[IO.File]::ReadAllText($p,(New-Object Text.UTF8Encoding($false)))
      [IO.File]::WriteAllText($p,$t,(New-Object Text.UTF8Encoding($true)))
#>
[CmdletBinding()]
param(
    [string] $Source,
    [string] $Destination,
    [switch] $Force,
    [switch] $SkipRebrand,
    [switch] $DryRun
)

$ErrorActionPreference = 'Stop'

# ============================================================
# 工具函数
# ============================================================

$script:Warnings     = New-Object System.Collections.Generic.List[string]
$script:ChangedFiles = 0

function Say { param([string]$m) Write-Host $m }

function SayH {
    param([string]$m)
    Write-Host ''
    Write-Host ('=' * 70)
    Write-Host $m
    Write-Host ('=' * 70)
}

function Warn {
    param([string]$m)
    $script:Warnings.Add($m)
    Write-Host ('  [warn] ' + $m) -ForegroundColor Yellow
}

# ------------------------------------------------------------------
# 「字节视图」字符串替换
#
# 问题：源文件是 UTF-8 且无 BOM，里面的中文是 3 字节序列。
#       如果用 [IO.File]::ReadAllText(UTF8) 读成 .NET 字符串再替换，
#       写回时整份文件会被重新编码 —— 一旦源文件不是 UTF-8 就会损坏。
#       如果直接按字节读成 ISO-8859-1 字符串（1 字节 ↔ 1 码位），
#       那 ASCII 能替换，但中文会变成 U+00E6 之类的散码，匹配不上。
#
# 做法：文件读成 ISO-8859-1（字节无损），
#       替换用的「旧串/新串」也先编码成 UTF-8 再按 ISO-8859-1 解读，
#       这样两边处在同一个「字节视图」里，中文与 ASCII 都能精确匹配；
#       写回时再反向转字节，全程零重编码、零 BOM。
# ------------------------------------------------------------------
$Latin1    = [System.Text.Encoding]::GetEncoding(28591)
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function ConvertTo-ByteView {
    param([string]$Text)
    return $Latin1.GetString([System.Text.Encoding]::UTF8.GetBytes($Text))
}

function Get-TextByteView {
    param([string]$Path)
    return $Latin1.GetString([System.IO.File]::ReadAllBytes($Path))
}

function Set-TextByteView {
    param([string]$Path, [string]$Text)
    [System.IO.File]::WriteAllBytes($Path, $Latin1.GetBytes($Text))
}

# 扁平参数：偶数位=旧串，奇数位=新串（避免嵌套数组在 PowerShell 里被解包）
function Invoke-TextReplace {
    param(
        [string]   $Path,
        [string[]] $Pairs
    )
    if (($Pairs.Count % 2) -ne 0) {
        throw ('替换对必须成对出现：' + $Path)
    }
    $old = Get-TextByteView $Path
    $new = $old
    for ($i = 0; $i -lt $Pairs.Count; $i += 2) {
        $new = $new.Replace((ConvertTo-ByteView $Pairs[$i]), (ConvertTo-ByteView $Pairs[$i + 1]))
    }
    if ($new -cne $old) {
        Set-TextByteView $Path $new
        $script:ChangedFiles++
        return $true
    }
    return $false
}

function Read-TextUtf8 {
    param([string]$Path)
    return [System.IO.File]::ReadAllText($Path, $Utf8NoBom)
}

# ============================================================
# 1. 定位源目录 / 目标目录，并做安全检查
# ============================================================

if (-not $Source) {
    if ($PSScriptRoot -and (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'photo-share-backend'))) {
        $Source = $PSScriptRoot
    } else {
        $Source = (Get-Location).Path
    }
}

if (-not (Test-Path -LiteralPath $Source)) {
    throw ('源目录不存在：' + $Source)
}
$Source = (Resolve-Path -LiteralPath $Source).Path

foreach ($must in @('photo-share-backend', 'photo-share-frontend', 'schema.sql')) {
    if (-not (Test-Path -LiteralPath (Join-Path $Source $must))) {
        throw ('源目录里找不到「' + $must + '」，这不是毕设目录。请用 -Source 显式指定。')
    }
}

if (-not $Destination) {
    $Destination = Join-Path (Split-Path -Parent $Source) 'Wayfare'
}
$Destination = [System.IO.Path]::GetFullPath($Destination)

# --- 目标目录护栏 ---
if ($Destination.TrimEnd('\') -ieq $Source.TrimEnd('\')) {
    throw '目标目录不能等于源目录。'
}
if ($Source.StartsWith($Destination.TrimEnd('\') + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw ('目标目录不能是源目录的父级（会把源目录包进去）。目标=' + $Destination + ' 源=' + $Source)
}
if ((Split-Path -Parent $Destination) -eq $Destination) {
    throw ('目标目录不能是盘符根目录：' + $Destination)
}

$destExists    = Test-Path -LiteralPath $Destination
$destItemCount = 0
if ($destExists) {
    $destItemCount = @(Get-ChildItem -LiteralPath $Destination -Force -ErrorAction SilentlyContinue).Count
    if ($destItemCount -gt 0 -and -not $Force) {
        throw ('目标目录已存在且非空：' + $Destination + "`n" +
               '（' + $destItemCount + ' 个顶层条目）' + "`n" +
               '脚本不会覆盖已有内容。确认要重建请加 -Force。')
    }
}

# --- 源目录完整性快照（只统计数量，不读取内容） ---
Say '正在统计源目录文件数（用于结束时的完整性校验）...'
$srcCountBefore = @(Get-ChildItem -LiteralPath $Source -Recurse -File -Force -ErrorAction SilentlyContinue).Count

# ============================================================
# 2. 迁移计划（数据化，DryRun 与正式执行共用同一份清单）
# ============================================================

$dirPlan = @(
    'db'
    'docs'
    'docs\refs'
    'scripts'
    'wayfare-backend'
    'wayfare-backend\src\main\java\com\wayfare'
    'wayfare-backend\src\main\resources'
    'wayfare-backend\uploads'
    'wayfare-frontend'
)

$filePlan = @(
    @{ From = 'Wayfare-重构提示词.md';                                  To = 'docs\Wayfare-重构提示词.md' }
    @{ From = 'Wayfare-开发文档.md';                                    To = 'docs\Wayfare-开发文档.md' }
    @{ From = 'Wayfare-文件迁移清单.md';                                To = 'docs\Wayfare-文件迁移清单.md' }
    @{ From = 'schema.sql';                                             To = 'db\schema.sql' }
    @{ From = '需求分析文档.md';                                        To = 'docs\refs\需求分析文档-摄影平台版.md' }
    @{ From = '技术文档-学习版.md';                                     To = 'docs\refs\技术文档-学习版.md' }
    @{ From = 'photo-share-backend\src\main\resources\application.yml'; To = 'docs\refs\application.yml-摄影平台版.yml' }
    @{ From = 'photo-share-frontend\index.html';                        To = 'wayfare-frontend\index.html' }
    @{ From = 'photo-share-frontend\package.json';                      To = 'wayfare-frontend\package.json' }
    @{ From = 'photo-share-frontend\package-lock.json';                 To = 'wayfare-frontend\package-lock.json' }
    @{ From = 'photo-share-frontend\vite.config.js';                    To = 'wayfare-frontend\vite.config.js' }
)

$treePlan = @(
    @{ From = 'photo-share-backend\src\main\java\com\photoshare'; To = 'wayfare-backend\src\main\java\com\wayfare'; Label = '后端 Java 源码' }
    @{ From = 'uploads';                                          To = 'wayfare-backend\uploads';                   Label = '上传测试图' }
    @{ From = 'photo-share-backend\接口测试结果';                 To = 'docs\refs\接口测试结果';                     Label = '接口测试截图' }
    @{ From = 'photo-share-frontend\src';                         To = 'wayfare-frontend\src';                      Label = '前端源码' }
)

SayH '迁移计划'
Say ('  源目录  : ' + $Source + '   （' + $srcCountBefore + ' 个文件）')
Say ('  目标目录: ' + $Destination)
if ($destExists) {
    if ($destItemCount -eq 0) {
        Say '  目标目录已存在但为空，可直接写入。'
    } else {
        Say ('  目标目录已存在，含 ' + $destItemCount + ' 个顶层条目，-Force 已指定：')
        Say ('    会先把它改名备份为 ' + (Split-Path $Destination -Leaf) + '.__backup_<时间戳>')
        Say  '    再重建一个干净的目标目录。（不删除，随时可以改回来）'
    }
}
Say ''
Say '  [建目录]'
foreach ($d in $dirPlan) { Say ('    ' + $d + '\') }
Say ''
Say '  [单文件复制]'
foreach ($f in $filePlan) { Say ('    ' + $f.From + '  →  ' + $f.To) }
Say ''
Say '  [目录树复制]'
foreach ($t in $treePlan) { Say ('    ' + $t.Label + '：' + $t.From + '\  →  ' + $t.To + '\') }
Say ''
Say '  [内容改写]'
Say '    后端 *.java          : com.photoshare → com.wayfare'
Say '                           PhotoShareApplication → WayfareApplication'
Say '                           "photo-share-backend" → "wayfare-backend"'
Say '                           摄影作品分享平台 → Wayfare（启动类注释与横幅）'
Say '                           FileStorageProperties 水印默认值 → 行走集 Wayfare'
Say '                           文件重命名 PhotoShareApplication.java → WayfareApplication.java'
Say '    db\schema.sql        : photo_share → wayfare（只改建库名，14 张表名不动）'
if (-not $SkipRebrand) {
    Say '    前端界面文案         : 光影集 → 行走集 / 摄影作品分享平台 → 行走集'
    Say '                           面向青年创作者的摄影作品分享平台 → AI 旅游攻略分享平台'
    Say '                           发现精彩摄影作品 → 发现精彩旅行攻略'
    Say '                           创建账号，开始分享你的摄影作品 → …你的旅行攻略'
    Say '                           © 2024 Photo Share → © 2024 Wayfare'
} else {
    Say '    前端界面文案         : 已跳过（-SkipRebrand）'
}
Say '    index.html <title>   : 行走集 Wayfare'
Say '    package.json name    : wayfare-frontend（package-lock.json 同步改）'
Say '    .gitignore           : 重写'
Say ''
Say '  [有意不复制]  target/  .idea/  node_modules/  *.log  旧版提示词与文档  旧 pom.xml'
Say '                （node_modules 请在 wayfare-frontend 下跑 npm install 重建）'

if ($DryRun) {
    SayH 'DryRun 模式：未写入任何文件。去掉 -DryRun 即正式执行。'
    exit 0
}

# ============================================================
# 3. 执行
# ============================================================

SayH '开始执行'

# --- 3.0 目标目录已存在且非空时：改名备份，绝不删除 ---
# 为什么不删：-Force 常用于「重跑一次」。用户很可能已经在旧目录里写了代码，
# 直接删掉就是不可逆的数据丢失。改名成带时间戳的备份是零风险做法，
# 确认新目录没问题后用户自己删备份即可。
$backupPath = $null
if ($destExists -and $destItemCount -gt 0) {
    $stamp     = Get-Date -Format 'yyyyMMdd-HHmmss'
    # 注意：Rename-Item 的参数是 -NewName（只给新名字），不是 -Destination（那是
    # Copy-Item / Move-Item 的参数）。写成 -Destination 会在运行时参数绑定失败。
    $backupLeaf = (Split-Path $Destination -Leaf) + '.__backup_' + $stamp
    $backupPath = Join-Path (Split-Path $Destination -Parent) $backupLeaf
    Say '备份已存在的目标目录（改名，不删除）：'
    Say ('  ' + $Destination + '  →  ' + $backupLeaf)
    Rename-Item -LiteralPath $Destination -NewName $backupLeaf -ErrorAction Stop
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    Say ('  备份位置：' + $backupPath)
}

# --- 3.1 建目录骨架 ---
Say '① 创建目录骨架'
foreach ($d in $dirPlan) {
    $full = Join-Path $Destination $d
    if (-not (Test-Path -LiteralPath $full)) {
        New-Item -ItemType Directory -Path $full -Force | Out-Null
    }
}
Say ('   已就绪 ' + $dirPlan.Count + ' 个目录')

# --- 3.2 单文件复制 ---
Say '② 复制文档与配置文件'
$copiedFiles = 0
foreach ($f in $filePlan) {
    $from = Join-Path $Source $f.From
    $to   = Join-Path $Destination $f.To
    if (-not (Test-Path -LiteralPath $from)) {
        Warn ('源文件不存在，已跳过：' + $f.From)
        continue
    }
    $toDir = Split-Path -Parent $to
    if (-not (Test-Path -LiteralPath $toDir)) {
        New-Item -ItemType Directory -Path $toDir -Force | Out-Null
    }
    Copy-Item -LiteralPath $from -Destination $to -Force
    $copiedFiles++
}
Say ('   已复制 ' + $copiedFiles + ' 个文件')

# 脚本自身留一份到 scripts\，作为「这次迁移怎么做的」的记录（迁移清单里有引用）
$selfPath = $PSCommandPath
if ($selfPath -and (Test-Path -LiteralPath $selfPath)) {
    $selfDstDir = Join-Path $Destination 'scripts'
    if (-not (Test-Path -LiteralPath $selfDstDir)) {
        New-Item -ItemType Directory -Path $selfDstDir -Force | Out-Null
    }
    Copy-Item -LiteralPath $selfPath -Destination (Join-Path $selfDstDir 'Init-Wayfare.ps1') -Force
    Say '   scripts\Init-Wayfare.ps1（脚本自身留档）'
}

# schema.sql：只改建库名与 USE 语句，14 张表名一个字都不动
# （表名不动是有意的 —— 搬过来的实体类就不用改 @TableName）
$schemaDst = Join-Path $Destination 'db\schema.sql'
if (Test-Path -LiteralPath $schemaDst) {
    $ok = Invoke-TextReplace $schemaDst @(
        'photo_share',                        'wayfare'
        '摄影作品分享平台 - 数据库建表脚本',   'Wayfare（行走集）- 数据库建表脚本'
    )
    if ($ok) {
        Say '   db\schema.sql 库名 photo_share → wayfare，表头注释已更新'
    } else {
        Warn 'db\schema.sql 未发生变化，请手工确认库名原本是否已是 wayfare'
    }
}

# --- 3.3 目录树复制 ---
Say '③ 复制目录树'
foreach ($t in $treePlan) {
    $from = Join-Path $Source $t.From
    $to   = Join-Path $Destination $t.To
    if (-not (Test-Path -LiteralPath $from)) {
        Warn ('源目录不存在，已跳过：' + $t.From)
        continue
    }
    $children = @(Get-ChildItem -LiteralPath $from -Force -ErrorAction SilentlyContinue)
    if ($children.Count -eq 0) {
        Warn ('源目录为空，已跳过：' + $t.From)
        continue
    }
    if (-not (Test-Path -LiteralPath $to)) {
        New-Item -ItemType Directory -Path $to -Force | Out-Null
    }
    Copy-Item -Path (Join-Path $from '*') -Destination $to -Recurse -Force
    $n = @(Get-ChildItem -LiteralPath $to -Recurse -File -Force -ErrorAction SilentlyContinue).Count
    Say ('   ' + $t.Label + '：目标现有 ' + $n + ' 个文件')
}

# --- 3.4 resources 占位（application.yml 由 P0-A 生成） ---
$resDir     = Join-Path $Destination 'wayfare-backend\src\main\resources'
$resGitkeep = Join-Path $resDir '.gitkeep'
if (-not (Test-Path -LiteralPath $resGitkeep)) {
    New-Item -ItemType File -Path $resGitkeep -Force | Out-Null
}

# --- 3.5 后端代码改写 ---
Say '④ 改写后端包名与命名'
$backendRoot = Join-Path $Destination 'wayfare-backend\src\main\java'

if (-not (Test-Path -LiteralPath $backendRoot)) {
    Warn '找不到后端源码目录，跳过改写'
} else {
    # 水印值的特例必须先做（否则会被通用规则吞掉）
    foreach ($f in @(Get-ChildItem -LiteralPath $backendRoot -Recurse -File -Filter 'FileStorageProperties.java')) {
        $ok = Invoke-TextReplace $f.FullName @('"摄影作品分享平台"', '"行走集 Wayfare"')
        if ($ok) { Say ('   水印默认值已改为「行走集 Wayfare」：' + $f.Name) }
    }

    $backendPairs = @(
        'com.photoshare',         'com.wayfare'
        'PhotoShareApplication',  'WayfareApplication'
        '"photo-share-backend"',  '"wayfare-backend"'
        '摄影作品分享平台',        'Wayfare '
    )

    $javaFiles = @(Get-ChildItem -LiteralPath $backendRoot -Recurse -File -Filter '*.java')
    $touched   = 0
    foreach ($f in $javaFiles) {
        if (Invoke-TextReplace $f.FullName $backendPairs) { $touched++ }
    }
    Say ('   扫描 ' + $javaFiles.Count + ' 个 .java，改动 ' + $touched + ' 个')

    # 启动类重命名
    $renamed = $false
    foreach ($f in @(Get-ChildItem -LiteralPath $backendRoot -Recurse -File -Filter 'PhotoShareApplication.java')) {
        $target = Join-Path $f.DirectoryName 'WayfareApplication.java'
        if (Test-Path -LiteralPath $target) {
            Warn ('重命名冲突，已保留原文件名：' + $f.FullName)
        } else {
            Move-Item -LiteralPath $f.FullName -Destination $target
            $renamed = $true
            Say '   PhotoShareApplication.java → WayfareApplication.java'
        }
    }
    if (-not $renamed) {
        $has = @(Get-ChildItem -LiteralPath $backendRoot -Recurse -File -Filter 'WayfareApplication.java').Count
        if ($has -eq 0) { Warn '没找到启动类文件（PhotoShareApplication.java / WayfareApplication.java）' }
    }

    if (Test-Path -LiteralPath (Join-Path $backendRoot 'com\photoshare')) {
        Warn '目标里仍存在 com\photoshare 目录，请检查'
    }
}

# --- 3.6 前端改写 ---
Say '⑤ 改写前端命名'
$feRoot = Join-Path $Destination 'wayfare-frontend'

# index.html 标题（结构性，不受 -SkipRebrand 影响）
$indexHtml = Join-Path $feRoot 'index.html'
if (Test-Path -LiteralPath $indexHtml) {
    if (Invoke-TextReplace $indexHtml @('<title>摄影作品分享平台</title>', '<title>行走集 Wayfare</title>')) {
        Say '   index.html 标题 → 行走集 Wayfare'
    }
}

# package.json / package-lock.json 包名（结构性）
foreach ($pj in @('package.json', 'package-lock.json')) {
    $pjPath = Join-Path $feRoot $pj
    if (Test-Path -LiteralPath $pjPath) {
        if (Invoke-TextReplace $pjPath @('photo-share-frontend', 'wayfare-frontend')) {
            Say ('   ' + $pj + ' 包名 → wayfare-frontend')
        }
    }
}

# 界面文案（可跳过）
if ($SkipRebrand) {
    Say '   界面文案改写：已跳过（-SkipRebrand）'
} else {
    $fePairs = @(
        '光影集 - 面向青年创作者的摄影作品分享平台', '行走集 - AI 旅游攻略分享平台'
        'Photo Share',                          'Wayfare'
        '加入光影集',                           '加入行走集'
        '光影集',                               '行走集'
        '摄影作品分享平台',                      '行走集'
        '发现精彩摄影作品',                      '发现精彩旅行攻略'
        '创建账号，开始分享你的摄影作品',          '创建账号，开始分享你的旅行攻略'
    )
    $feSrc = Join-Path $feRoot 'src'
    $feFiles = @()
    if (Test-Path -LiteralPath $feSrc) {
        $feFiles = @(Get-ChildItem -LiteralPath $feSrc -Recurse -File |
            Where-Object { @('.vue', '.js', '.html', '.scss') -contains $_.Extension })
    }
    $touched = 0
    foreach ($f in $feFiles) {
        if (Invoke-TextReplace $f.FullName $fePairs) {
            $touched++
            Say ('   文案改写：' + $f.FullName.Substring($feRoot.Length + 1))
        }
    }
    Say ('   扫描 ' + $feFiles.Count + ' 个前端文件，改动 ' + $touched + ' 个')
}

# --- 3.7 写 .gitignore ---
Say '⑥ 写入 .gitignore'
$gitignore = @'
# ===== Java / Maven =====
target/
*.class
*.jar
*.war
*.ear
*.iml
.idea/
*.ipr
*.iws
.mvn/
mvnw
mvnw.cmd

# ===== Node / Frontend =====
node_modules/
dist/
dist-ssr/
*.local
.npm
.cache/
.parcel-cache/

# ===== IDE / Editor =====
.vscode/
*.swp
*.swo
*~
.project
.classpath
.settings/

# ===== OS =====
.DS_Store
Thumbs.db
desktop.ini

# ===== Logs =====
*.log
logs/
npm-debug.log*
yarn-debug.log*
yarn-error.log*

# ===== Environment (never commit) =====
.env
.env.local
.env.*.local

# ===== Runtime uploads =====
wayfare-backend/uploads/

# ===== Temporary =====
tmp/
temp/
*.tmp
*.bak
'@
Set-TextByteView (Join-Path $Destination '.gitignore') ($gitignore -replace "`r`n", "`n")
Say '   完成'

# ============================================================
# 4. 校验
# ============================================================

SayH '校验'

# --- 4.1 旧命名残留扫描（只扫代码目录；docs/refs 里的参考资料不算） ---
# 分两档，别混在一起：
#   硬残留 = 旧品牌名（photo / 光影集）—— 迁移必须清零，出现就是 bug
#   软残留 = 旧业务语义（摄影 / 照片 / 拍摄）—— 属于内容域改写，
#            由 P0-B / P0-C / P5 处理，这里只做提示不算错误
# 一律大小写不敏感：旧品牌名有 photo-share / PhotoShare / Photo Share 三种写法
$hardPatterns = @('photo', '光影集')
$softPatterns = @('摄影', '照片', '拍摄', '光影')
$scanRoots = @(
    (Join-Path $Destination 'wayfare-backend\src')
    (Join-Path $Destination 'wayfare-frontend\src')
    (Join-Path $Destination 'wayfare-frontend\index.html')
    (Join-Path $Destination 'wayfare-frontend\package.json')
    (Join-Path $Destination 'wayfare-frontend\package-lock.json')
    (Join-Path $Destination 'db')
)

$hardHits = New-Object System.Collections.Generic.List[string]
$softHits = New-Object System.Collections.Generic.List[string]

foreach ($root in $scanRoots) {
    if (-not (Test-Path -LiteralPath $root)) { continue }
    $files = @()
    if ((Get-Item -LiteralPath $root).PSIsContainer) {
        $files = @(Get-ChildItem -LiteralPath $root -Recurse -File -Force)
    } else {
        $files = @(Get-Item -LiteralPath $root)
    }
    foreach ($f in $files) {
        $text = Read-TextUtf8 $f.FullName
        $rel  = $f.FullName.Substring($Destination.Length + 1)
        $isHard = $false
        foreach ($p in $hardPatterns) {
            if ($text.IndexOf($p, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
                $hardHits.Add('  [硬残留] ' + $p + '  →  ' + $rel)
                $isHard = $true
                break
            }
        }
        if ($isHard) { continue }
        foreach ($p in $softPatterns) {
            if ($text.IndexOf($p, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
                $softHits.Add('  [待改写] ' + $p + '  →  ' + $rel)
                break
            }
        }
    }
}
foreach ($h in $hardHits) { Say $h }
foreach ($s in $softHits) { Say $s }
Say ''
if ($hardHits.Count -eq 0) {
    Say '  OK  旧品牌名残留（photo / 光影集）：0 处'
} else {
    Warn ('旧品牌名残留 ' + $hardHits.Count + ' 处，见上方清单')
}
if ($softHits.Count -eq 0) {
    Say '  OK  旧业务语义残留（摄影 / 照片 / 拍摄）：0 处'
} else {
    Say ('  提示 旧业务语义残留 ' + $softHits.Count + ' 处 —— 属内容域改写范围，')
    Say '       由 P0-B / P0-C / P5 处理，不算迁移错误。'
}

# --- 4.2 包名与目录一致性 ---
$comWayfare = Join-Path $Destination 'wayfare-backend\src\main\java\com\wayfare'
if (Test-Path -LiteralPath $comWayfare) {
    $javaAll = @(Get-ChildItem -LiteralPath $comWayfare -Recurse -File -Filter '*.java')
    $pkgBad  = 0
    foreach ($f in $javaAll) {
        if ((Read-TextUtf8 $f.FullName) -notmatch 'package\s+com\.wayfare') { $pkgBad++ }
    }
    Say ('  com\wayfare 下 .java：' + $javaAll.Count + ' 个；package 声明异常：' + $pkgBad + ' 个')
    if ($pkgBad -gt 0) { Warn '有 .java 的 package 声明不是 com.wayfare' }
} else {
    Warn '找不到 com\wayfare 目录'
}

# --- 4.3 启动类 ---
$appCls = Join-Path $comWayfare 'WayfareApplication.java'
if (Test-Path -LiteralPath $appCls) {
    if ((Read-TextUtf8 $appCls).Contains('class WayfareApplication')) {
        Say '  OK  WayfareApplication.java 类名正确'
    } else {
        Warn 'WayfareApplication.java 里的类名没改对'
    }
} else {
    Warn '找不到 WayfareApplication.java'
}

# --- 4.4 schema.sql 库名与表名 ---
$schemaDst = Join-Path $Destination 'db\schema.sql'
if (Test-Path -LiteralPath $schemaDst) {
    $t     = Read-TextUtf8 $schemaDst
    $okDb  = $t.Contains('CREATE DATABASE IF NOT EXISTS wayfare') -and $t.Contains('USE wayfare;')
    $okTbl = $t.Contains('CREATE TABLE sys_user') -and $t.Contains('CREATE TABLE work')
    if ($okDb -and $okTbl) {
        Say '  OK  schema.sql：库名已改为 wayfare，表名未改动'
    } else {
        if (-not $okDb)  { Warn 'schema.sql 库名没改对' }
        if (-not $okTbl) { Warn 'schema.sql 表名疑似被改动（不应该）' }
    }
} else {
    Warn '找不到 db\schema.sql'
}

# --- 4.5 前端文案抽查（证明字节视图替换真的生效了） ---
$probe = Join-Path $Destination 'wayfare-frontend\src\layouts\DefaultLayout.vue'
if (Test-Path -LiteralPath $probe) {
    $t = Read-TextUtf8 $probe
    if ($t.Contains('行走集') -and $t.Contains('© 2024 Wayfare')) {
        Say '  OK  前端中英文改写均生效（DefaultLayout.vue）'
    } elseif ($t.Contains('行走集')) {
        Warn '前端中文改写已生效，但英文版权行未改（检查 Photo Share 规则）'
    } else {
        Warn '前端中文改写未生效，请检查 ConvertTo-ByteView 逻辑'
    }
}

# --- 4.6 源目录完整性 ---
$srcCountAfter = @(Get-ChildItem -LiteralPath $Source -Recurse -File -Force -ErrorAction SilentlyContinue).Count
Say ''
if ($srcCountAfter -eq $srcCountBefore) {
    Say ('  OK  源目录未被改动：执行前后均为 ' + $srcCountBefore + ' 个文件')
} else {
    Warn ('源目录文件数发生变化：' + $srcCountBefore + ' → ' + $srcCountAfter + '（不应该发生！）')
}

# --- 4.7 目标结构 ---
SayH '目标目录结构'
function Show-Tree {
    param(
        [string] $Dir,
        [string] $Prefix,
        [int]    $Depth
    )
    if ($Depth -gt 6) { return }
    $dirs = @(Get-ChildItem -LiteralPath $Dir -Directory -Force -ErrorAction SilentlyContinue |
        Where-Object { @('node_modules', '.git', 'target', 'dist') -notcontains $_.Name } |
        Sort-Object Name)
    foreach ($d in $dirs) {
        Say ('  ' + $Prefix + $d.Name + '\')
        Show-Tree -Dir $d.FullName -Prefix ($Prefix + '    ') -Depth ($Depth + 1)
    }
}
Show-Tree -Dir $Destination -Prefix '' -Depth 0

# ============================================================
# 5. 汇总
# ============================================================
SayH '完成'
Say ('  目标目录        : ' + $Destination)
Say ('  改写过的文件数  : ' + $script:ChangedFiles)
if ($backupPath) {
    Say ''
    Say ('  旧目录已备份为  : ' + (Split-Path $backupPath -Leaf))
    Say ('                    ' + $backupPath)
    Say  '  确认新目录没问题后，可以自己把这份备份删掉。'
}
Say ''
Say '  接下来（手动）：'
Say '    1) 按 P0-A 重写 wayfare-backend\pom.xml（artifactId=wayfare-backend）'
Say '       与 wayfare-backend\src\main\resources\application.yml（库名 wayfare + GLM/DeepSeek 配置）'
Say '    2) 执行 db\schema.sql 建库（库名已改为 wayfare）'
Say '    3) cd wayfare-frontend ; npm install'
Say '    4) 后端 mvn spring-boot:run → http://localhost:8080/api/health'
Say '    5) 在 Wayfare 目录 git init'
Say ''
Say '  参考文档：docs\Wayfare-文件迁移清单.md'
Say '  下一步  ：打开 docs\Wayfare-重构提示词.md，投喂 P0-A 任务块'

if ($script:Warnings.Count -gt 0) {
    Say ''
    Say ('  警告 ' + $script:Warnings.Count + ' 条：')
    foreach ($w in $script:Warnings) { Say ('    - ' + $w) }
}

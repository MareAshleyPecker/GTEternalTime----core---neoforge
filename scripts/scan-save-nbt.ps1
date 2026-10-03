# 直接读 Minecraft 存档的区块 NBT，用来在不开游戏的情况下验证「数据到底写没写进存档」。
#
# 用法：
#   $env:WORLD   = "run\saves\新的世界"                # 存档目录（含 region 子目录）
#   $env:NEEDLES = "testValue,gtetcore:test_sync_part"  # 要搜的字符串，逗号分隔
#   $env:MAXPRINT = "5"                                 # 命中时打印几处上下文
#   .\scripts\scan-save-nbt.ps1
#
# 输出里每处命中会给出命中点前后的十六进制与可见字符，可以直接读字段和值，例如：
#   03 00 09 "testValue" 00 00 00 03   → TAG_Int testValue = 3
#
# 坑（踩过一次）：PowerShell 的 -shl 会把结果截断回左操作数的类型，[byte]2 -shl 8 等于 0，
# 所以读区域文件头部的偏移与长度必须先 ( [int]$b[...] ) 转换，否则解析出的一堆“区块”全是垃圾片段。
Add-Type -AssemblyName System.IO.Compression

$world = $env:WORLD
$needles = $env:NEEDLES -split ","
$maxPrint = [int]$env:MAXPRINT
if (-not $maxPrint) { $maxPrint = 5 }
if (-not $world) { throw "请先设置环境变量 WORLD（存档目录）" }

function Get-Int32BE([byte[]]$b, [int]$p) {
    return ([int]$b[$p] -shl 24) -bor ([int]$b[$p + 1] -shl 16) -bor ([int]$b[$p + 2] -shl 8) -bor [int]$b[$p + 3]
}

function Get-ChunkNbt([byte[]]$bytes, [int]$pos, [int]$len, [int]$comp) {
    $dataLen = $len - 1
    if ($dataLen -le 0) { return $null }
    $payload = New-Object byte[] $dataLen
    [Array]::Copy($bytes, $pos + 5, $payload, 0, $dataLen)
    $ms = New-Object System.IO.MemoryStream(, $payload)
    try {
        if ($comp -eq 2) { $ds = New-Object System.IO.Compression.ZLibStream($ms, [System.IO.Compression.CompressionMode]::Decompress) }
        elseif ($comp -eq 1) { $ds = New-Object System.IO.Compression.GZipStream($ms, [System.IO.Compression.CompressionMode]::Decompress) }
        else { return $payload }
        $out = New-Object System.IO.MemoryStream
        $ds.CopyTo($out)
        $ds.Dispose()
        return $out.ToArray()
    } catch { return $null }
}

$chunks = 0; $failed = 0; $maxSize = 0
$counts = @{}; foreach ($n in $needles) { $counts[$n] = 0 }
$printed = 0

foreach ($f in (Get-ChildItem (Join-Path $world "region") -Filter "*.mca")) {
    $bytes = [System.IO.File]::ReadAllBytes($f.FullName)
    for ($i = 0; $i -lt 1024; $i++) {
        $off = ([int]$bytes[$i * 4] -shl 16) -bor ([int]$bytes[$i * 4 + 1] -shl 8) -bor [int]$bytes[$i * 4 + 2]
        if ($off -eq 0) { continue }
        $pos = $off * 4096
        if ($pos + 5 -gt $bytes.Length) { continue }
        $len = Get-Int32BE $bytes $pos
        if ($len -le 1 -or $pos + 5 + $len -gt $bytes.Length) { continue }
        $nbt = Get-ChunkNbt $bytes $pos $len ([int]$bytes[$pos + 4])
        if ($null -eq $nbt) { $failed++; continue }
        $chunks++
        if ($nbt.Length -gt $maxSize) { $maxSize = $nbt.Length }
        $str = [System.Text.Encoding]::Latin1.GetString($nbt)
        foreach ($n in $needles) {
            $at = $str.IndexOf($n)
            if ($at -lt 0) { continue }
            $counts[$n]++
            if ($printed -lt $maxPrint) {
                $printed++
                $cx = $i % 32; $cz = [math]::Floor($i / 32)
                "=== $($f.Name) 区块($cx,$cz) 命中 '$n'  区块NBT $($nbt.Length) 字节 ==="
                $from = [Math]::Max(0, $at - 40)
                $take = [Math]::Min(240, $str.Length - $from)
                $seg = $str.Substring($from, $take)
                "  HEX: " + (($seg.ToCharArray() | ForEach-Object { "{0:X2}" -f [int][char]$_ }) -join " ")
                "  TXT: " + (($seg.ToCharArray() | ForEach-Object { if ([int][char]$_ -ge 32 -and [int][char]$_ -lt 127) { $_ } else { "." } }) -join "")
            }
        }
    }
}

"===== 统计 ====="
"世界: $world"
"成功解压区块: $chunks   解压失败: $failed   最大区块NBT: $maxSize 字节"
foreach ($n in $needles) { "命中 '$n' 的区块数: $($counts[$n])" }

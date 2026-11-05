param(
    [Parameter(Mandatory=$true)][string]$InputBundle,
    [Parameter(Mandatory=$true)][string]$OutputBundle
)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression
$inputPath=[System.IO.Path]::GetFullPath($InputBundle)
$outputPath=[System.IO.Path]::GetFullPath($OutputBundle)
if ([System.IO.Path]::GetExtension($inputPath) -ne '.aab' -or [System.IO.Path]::GetExtension($outputPath) -ne '.aab' -or $inputPath -eq $outputPath) { throw 'Use separate input/output Android App Bundle files. Never apply this to an APK.' }
if (Test-Path -LiteralPath $outputPath) { throw 'Output already exists' }
$source=[System.IO.Compression.ZipFile]::OpenRead($inputPath)
try {
    if (!$source.GetEntry('META-INF/MANIFEST.MF') -or !$source.GetEntry('BundleConfig.pb')) { throw 'Not a signed Android App Bundle' }
    $destination=[System.IO.Compression.ZipFile]::Open($outputPath,[System.IO.Compression.ZipArchiveMode]::Create)
    try {
        # JAR stream verification expects the manifest/signature metadata first.
        # Reordering an AAB does not change its JAR-signed entry payloads.
        $entries=$source.Entries | Sort-Object @{Expression={
            if ($_.FullName -eq 'META-INF/MANIFEST.MF') {0}
            elseif ($_.FullName -match '^META-INF/.*\.SF$') {1}
            elseif ($_.FullName -match '^META-INF/.*\.(RSA|DSA|EC)$') {2}
            elseif ($_.FullName.StartsWith('META-INF/')) {3} else {4}
        }},FullName
        foreach ($entry in $entries) {
            $copy=$destination.CreateEntry($entry.FullName,[System.IO.Compression.CompressionLevel]::Optimal)
            $copy.LastWriteTime=$entry.LastWriteTime; $copy.ExternalAttributes=$entry.ExternalAttributes
            $read=$entry.Open(); $write=$copy.Open()
            try { $read.CopyTo($write) } finally { $read.Dispose(); $write.Dispose() }
        }
    } finally { $destination.Dispose() }
    $check=[System.IO.Compression.ZipFile]::OpenRead($outputPath)
    try {
        if ($source.Entries.Count -ne $check.Entries.Count) { throw 'Entry count changed' }
        foreach ($entry in $source.Entries) {
            $a=$entry.Open(); $b=$check.GetEntry($entry.FullName).Open()
            $hash=[System.Security.Cryptography.SHA256]::Create()
            try {
                $before=[BitConverter]::ToString($hash.ComputeHash($a))
                $after=[BitConverter]::ToString($hash.ComputeHash($b))
                if ($before -ne $after) { throw ('Entry payload changed: '+$entry.FullName) }
            } finally { $a.Dispose(); $b.Dispose(); $hash.Dispose() }
        }
    } finally { $check.Dispose() }
} finally { $source.Dispose() }
'AAB archive order normalized; all signed entry payloads remain identical.'

# Optional Windows-only regeneration helper. Build uses the committed vector; no font is bundled.
Add-Type -AssemblyName System.Drawing
$brandPaths = @()
foreach ($brandLine in @(@('CarSync Music', 20, 12, '#F2F5FA', 1), @('by Brayo', 16, 46, '#B4F36D', 0))) {
    $brandPath = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $brandPath.AddString($brandLine[0], [System.Drawing.FontFamily]::new('Segoe UI'), [int]$brandLine[4], [single]$brandLine[1], [System.Drawing.PointF]::new(0,0), [System.Drawing.StringFormat]::GenericTypographic)
    $brandBounds = $brandPath.GetBounds()
    $brandTransform = [System.Drawing.Drawing2D.Matrix]::new()
    $brandTransform.Translate([single]((200-$brandBounds.Width)/2-$brandBounds.X), [single]($brandLine[2]-$brandBounds.Y))
    $brandPath.Transform($brandTransform)
    $brandPoints = $brandPath.PathPoints; $brandTypes = $brandPath.PathTypes; $brandData = ''
    function Format-BrandPoint($point) { return $point.X.ToString('0.###', [System.Globalization.CultureInfo]::InvariantCulture) + ',' + $point.Y.ToString('0.###', [System.Globalization.CultureInfo]::InvariantCulture) }
    for ($brandIndex=0; $brandIndex -lt $brandPoints.Count; $brandIndex++) {
        $brandKind = $brandTypes[$brandIndex] -band 7
        if ($brandKind -eq 0) { $brandData += 'M' + (Format-BrandPoint $brandPoints[$brandIndex]) }
        elseif ($brandKind -eq 1) { $brandData += 'L' + (Format-BrandPoint $brandPoints[$brandIndex]) }
        elseif ($brandKind -eq 3) { $brandData += 'C' + (Format-BrandPoint $brandPoints[$brandIndex]) + ' ' + (Format-BrandPoint $brandPoints[$brandIndex+1]) + ' ' + (Format-BrandPoint $brandPoints[$brandIndex+2]); $brandIndex += 2 }
        if (($brandTypes[$brandIndex] -band 128) -ne 0) { $brandData += 'Z' }
    }
    $brandPaths += '    <path android:fillColor="' + $brandLine[3] + '" android:pathData="' + $brandData + '" />'
    $brandPath.Dispose(); $brandTransform.Dispose()
}
$brandVector = '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="200dp" android:height="80dp" android:viewportWidth="200" android:viewportHeight="80">' + "`n" + ($brandPaths -join "`n") + "`n</vector>"
$brandTarget = Join-Path $PSScriptRoot '../android/app/src/main/res/drawable/brand_wordmark.xml'
Set-Content -LiteralPath $brandTarget -Value $brandVector

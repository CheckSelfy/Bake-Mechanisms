param(
    [string]$InputJson = 'run/stasis-smoke/plugins/BakeStasisSmoke/slime-gallery.json',
    [string]$OutputPng = 'run/issue32-slime-faces.png'
)
# Windows-only schematic from actual Paper blocks exported by SlimeFillerCheck.
# This is a geometry review aid; /slimegallery is the Minecraft visual acceptance tool.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$models = Get-Content -LiteralPath $InputJson -Raw | ConvertFrom-Json
$bitmap = [System.Drawing.Bitmap]::new(1080, 1140)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$titleFont = [System.Drawing.Font]::new('Segoe UI', 18)
$font = [System.Drawing.Font]::new('Segoe UI', 12)
$text = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(225, 233, 222))
$outline = [System.Drawing.Pen]::new([System.Drawing.Color]::FromArgb(35, 0, 0, 0))
$colors = @{
    LIME_STAINED_GLASS = '#97c84b'; GREEN_STAINED_GLASS = '#57783f'
    BLACK_STAINED_GLASS = '#151c13'; LIME_TERRACOTTA = '#678b3c'
    GREEN_CONCRETE = '#455c27'; BLACK_CONCRETE = '#111611'
}
try {
    $graphics.Clear([System.Drawing.Color]::FromArgb(24, 31, 25))
    $graphics.DrawString('Slime filler: actual front-face block layout', $titleFont, $text, 20, 10)
    $graphics.DrawString('Schematic only. Inspect textures/transparency in Minecraft with /slimegallery 1..9.', $font, $text, 20, 43)
    for ($i = 0; $i -lt $models.Count; $i++) {
        $model = $models[$i]
        $left = 15 + ($i % 3) * 360
        $top = 85 + [Math]::Floor($i / 3) * 350
        $size = $model.size
        $width = if ($model.front -in @('EAST', 'WEST')) { $size[2] } else { $size[0] }
        $height = $size[1]
        $cell = [Math]::Max(1, [Math]::Floor([Math]::Min(320 / $width, 265 / $height)))
        $originX = $left + (330 - $width * $cell) / 2
        $originY = $top + 38 + (265 - $height * $cell) / 2
        $graphics.DrawString("$($i+1). $($size -join ' x ') - $($model.front) / $($model.pattern)", $font, $text, $left + 4, $top)
        foreach ($voxel in $model.voxels) {
            $x, $y, $z, $material = $voxel
            $visible = switch ($model.front) {
                'NORTH' { $z -eq 0 }; 'SOUTH' { $z -eq $size[2]-1 }
                'WEST' { $x -eq 0 }; 'EAST' { $x -eq $size[0]-1 }
            }
            if (!$visible) { continue }
            $u = if ($model.front -in @('EAST', 'WEST')) { $z } else { $x }
            $color = if ($colors.ContainsKey($material)) { $colors[$material] } else { '#b0b0b0' }
            $brush = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml($color))
            try {
                $px = [int]($originX + $u * $cell); $py = [int]($originY + ($height - 1 - $y) * $cell)
                $graphics.FillRectangle($brush, $px, $py, [int]$cell, [int]$cell)
                $graphics.DrawRectangle($outline, $px, $py, [int]$cell, [int]$cell)
            } finally { $brush.Dispose() }
        }
    }
    $target = [System.IO.Path]::GetFullPath($OutputPng)
    [System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($target)) | Out-Null
    $bitmap.Save($target, [System.Drawing.Imaging.ImageFormat]::Png)
    Write-Output $target
} finally {
    $outline.Dispose(); $text.Dispose(); $font.Dispose(); $titleFont.Dispose(); $graphics.Dispose(); $bitmap.Dispose()
}

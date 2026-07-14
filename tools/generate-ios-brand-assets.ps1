[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

Add-Type -AssemblyName System.Drawing.Common

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$assetCatalog = Join-Path $repositoryRoot 'iosApp\Kwabor\Resources\Assets.xcassets'
$appIconDirectory = Join-Path $assetCatalog 'AppIcon.appiconset'
$launchMarkDirectory = Join-Path $assetCatalog 'LaunchMark.imageset'

New-Item -ItemType Directory -Force -Path $appIconDirectory, $launchMarkDirectory | Out-Null

function New-KwaborBrandBitmap {
    param(
        [Parameter(Mandatory)]
        [int]$Size,

        [Parameter(Mandatory)]
        [string]$OutputPath,

        [Parameter(Mandatory)]
        [bool]$OpaqueBackground
    )

    $bitmap = [System.Drawing.Bitmap]::new(
        $Size,
        $Size,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb
    )
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $markPath = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $windingPath = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $whiteBrush = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::White)
    $inkPen = $null

    try {
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $backgroundColor =
            if ($OpaqueBackground) {
                [System.Drawing.Color]::FromArgb(255, 14, 14, 16)
            } else {
                [System.Drawing.Color]::Transparent
            }
        $graphics.Clear($backgroundColor)

        $scale = $Size / 108.0
        [System.Drawing.PointF[]]$markPoints = @(
            [System.Drawing.PointF]::new(31 * $scale, 25 * $scale),
            [System.Drawing.PointF]::new(44 * $scale, 25 * $scale),
            [System.Drawing.PointF]::new(44 * $scale, 47 * $scale),
            [System.Drawing.PointF]::new(63 * $scale, 25 * $scale),
            [System.Drawing.PointF]::new(78 * $scale, 25 * $scale),
            [System.Drawing.PointF]::new(53 * $scale, 52 * $scale),
            [System.Drawing.PointF]::new(80 * $scale, 83 * $scale),
            [System.Drawing.PointF]::new(64 * $scale, 83 * $scale),
            [System.Drawing.PointF]::new(44 * $scale, 58 * $scale),
            [System.Drawing.PointF]::new(44 * $scale, 83 * $scale),
            [System.Drawing.PointF]::new(31 * $scale, 83 * $scale)
        )
        $markPath.AddPolygon($markPoints)
        $graphics.FillPath($whiteBrush, $markPath)

        $windingPath.AddBezier(
            36 * $scale,
            79 * $scale,
            48 * $scale,
            68 * $scale,
            45 * $scale,
            57 * $scale,
            58 * $scale,
            49 * $scale
        )
        $windingPath.AddBezier(
            58 * $scale,
            49 * $scale,
            68 * $scale,
            43 * $scale,
            68 * $scale,
            34 * $scale,
            74 * $scale,
            28 * $scale
        )
        $inkPen = [System.Drawing.Pen]::new(
            [System.Drawing.Color]::FromArgb(255, 14, 14, 16),
            5 * $scale
        )
        $inkPen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
        $inkPen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
        $inkPen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
        $graphics.DrawPath($inkPen, $windingPath)

        $bitmap.Save($OutputPath, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        if ($null -ne $inkPen) {
            $inkPen.Dispose()
        }
        $whiteBrush.Dispose()
        $windingPath.Dispose()
        $markPath.Dispose()
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}

New-KwaborBrandBitmap `
    -Size 1024 `
    -OutputPath (Join-Path $appIconDirectory 'AppIcon-1024.png') `
    -OpaqueBackground $true

@(
    @{ Scale = '1x'; Size = 108 },
    @{ Scale = '2x'; Size = 216 },
    @{ Scale = '3x'; Size = 324 }
) | ForEach-Object {
    New-KwaborBrandBitmap `
        -Size $_.Size `
        -OutputPath (Join-Path $launchMarkDirectory "LaunchMark-$($_.Scale).png") `
        -OpaqueBackground $false
}

Write-Output 'Generated deterministic iOS Kwabor app icon and launch mark assets.'

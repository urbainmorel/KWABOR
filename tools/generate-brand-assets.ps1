[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

Add-Type -AssemblyName System.Drawing

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$masterAsset = Join-Path $repositoryRoot 'kwabor_icone_app.png'
$launchWordmarkMasterAsset = Join-Path $repositoryRoot 'kwabor_2.png'
$assetCatalog = Join-Path $repositoryRoot 'iosApp\Kwabor\Resources\Assets.xcassets'
$appIconDirectory = Join-Path $assetCatalog 'AppIcon.appiconset'
$launchMarkDirectory = Join-Path $assetCatalog 'LaunchMark.imageset'
$launchWordmarkDirectory = Join-Path $assetCatalog 'LaunchWordmark.imageset'
$androidNoDensityDirectory =
    Join-Path $repositoryRoot 'androidApp\src\main\res\drawable-nodpi'
$androidDensityAssets = @(
    @{ Density = 'mdpi'; BrandSize = 108; LaunchSize = 288 },
    @{ Density = 'hdpi'; BrandSize = 162; LaunchSize = 432 },
    @{ Density = 'xhdpi'; BrandSize = 216; LaunchSize = 576 },
    @{ Density = 'xxhdpi'; BrandSize = 324; LaunchSize = 864 },
    @{ Density = 'xxxhdpi'; BrandSize = 432; LaunchSize = 1152 }
)
$androidDrawableDirectories =
    $androidDensityAssets |
    ForEach-Object {
        Join-Path $repositoryRoot "androidApp\src\main\res\drawable-$($_.Density)"
    }
$outputDirectories = @(
    $appIconDirectory,
    $launchMarkDirectory,
    $launchWordmarkDirectory,
    $androidNoDensityDirectory
) + $androidDrawableDirectories

if (-not (Test-Path -LiteralPath $masterAsset -PathType Leaf)) {
    throw "Missing canonical Kwabor brand asset: $masterAsset"
}
if (-not (Test-Path -LiteralPath $launchWordmarkMasterAsset -PathType Leaf)) {
    throw "Missing canonical Kwabor launch wordmark: $launchWordmarkMasterAsset"
}

New-Item `
    -ItemType Directory `
    -Force `
    -Path $outputDirectories |
    Out-Null

function Export-KwaborBrandBitmap {
    param(
        [Parameter(Mandatory)]
        [int]$Size,

        [Parameter(Mandatory)]
        [string]$OutputPath
    )

    $source = [System.Drawing.Image]::FromFile($masterAsset)
    $bitmap = [System.Drawing.Bitmap]::new(
        $Size,
        $Size,
        [System.Drawing.Imaging.PixelFormat]::Format24bppRgb
    )
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $attributes = [System.Drawing.Imaging.ImageAttributes]::new()

    try {
        $attributes.SetWrapMode([System.Drawing.Drawing2D.WrapMode]::TileFlipXY)
        $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
        $graphics.DrawImage(
            $source,
            [System.Drawing.Rectangle]::new(0, 0, $Size, $Size),
            0,
            0,
            $source.Width,
            $source.Height,
            [System.Drawing.GraphicsUnit]::Pixel,
            $attributes
        )

        $bitmap.Save($OutputPath, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $attributes.Dispose()
        $graphics.Dispose()
        $bitmap.Dispose()
        $source.Dispose()
    }
}

function Export-KwaborPaddedBrandBitmap {
    param(
        [Parameter(Mandatory)]
        [int]$Size,

        [Parameter(Mandatory)]
        [string]$OutputPath,

        [ValidateRange(0.1, 1.0)]
        [double]$ContentScale = 1.0
    )

    $source = [System.Drawing.Image]::FromFile($masterAsset)
    $bitmap = [System.Drawing.Bitmap]::new(
        $Size,
        $Size,
        [System.Drawing.Imaging.PixelFormat]::Format24bppRgb
    )
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $attributes = [System.Drawing.Imaging.ImageAttributes]::new()

    try {
        $attributes.SetWrapMode([System.Drawing.Drawing2D.WrapMode]::TileFlipXY)
        $graphics.Clear([System.Drawing.Color]::FromArgb(14, 14, 13))
        $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
        $contentSize = [Math]::Round($Size * $ContentScale)
        $contentOffset = [Math]::Floor(($Size - $contentSize) / 2.0)
        $graphics.DrawImage(
            $source,
            [System.Drawing.Rectangle]::new(
                $contentOffset,
                $contentOffset,
                $contentSize,
                $contentSize
            ),
            0,
            0,
            $source.Width,
            $source.Height,
            [System.Drawing.GraphicsUnit]::Pixel,
            $attributes
        )

        $bitmap.Save($OutputPath, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $attributes.Dispose()
        $graphics.Dispose()
        $bitmap.Dispose()
        $source.Dispose()
    }
}

$androidDensityAssets | ForEach-Object {
    $outputDirectory =
        Join-Path $repositoryRoot "androidApp\src\main\res\drawable-$($_.Density)"
    Export-KwaborPaddedBrandBitmap `
        -Size $_.BrandSize `
        -OutputPath (Join-Path $outputDirectory 'kwabor_brand_mark.png') `
        -ContentScale 0.75
    Export-KwaborPaddedBrandBitmap `
        -Size $_.LaunchSize `
        -OutputPath (Join-Path $outputDirectory 'kwabor_launch_mark.png') `
        -ContentScale 0.75
}

$obsoleteNoDensityLaunchMark =
    Join-Path $androidNoDensityDirectory 'kwabor_launch_mark.png'
if (Test-Path -LiteralPath $obsoleteNoDensityLaunchMark -PathType Leaf) {
    Remove-Item -LiteralPath $obsoleteNoDensityLaunchMark -Force
}

Export-KwaborBrandBitmap `
    -Size 1024 `
    -OutputPath (Join-Path $appIconDirectory 'AppIcon-1024.png')

@(
    @{ Scale = '1x'; Size = 108 },
    @{ Scale = '2x'; Size = 216 },
    @{ Scale = '3x'; Size = 324 }
) | ForEach-Object {
    Export-KwaborBrandBitmap `
        -Size $_.Size `
        -OutputPath (Join-Path $launchMarkDirectory "LaunchMark-$($_.Scale).png")
}

Copy-Item `
    -LiteralPath $launchWordmarkMasterAsset `
    -Destination (Join-Path $androidNoDensityDirectory 'kwabor_launch_wordmark.png') `
    -Force
Copy-Item `
    -LiteralPath $launchWordmarkMasterAsset `
    -Destination (Join-Path $launchWordmarkDirectory 'LaunchWordmark.png') `
    -Force

Write-Output (
    'Generated deterministic Android and iOS assets, including the Android ' +
    '288 dp launch canvases, and copied the exact kwabor_2.png launch wordmark.'
)

# Android brand assets

## Kwabor launch mark

- Canonical build source: repository-root `kwabor_icone_app.png`, 1254 × 1254 px, RGB.
- Brand-owner confirmation that this repository source is the official high-definition master
  remains open; replacing it requires deliberate regeneration and review of every locked derivative.
- Canonical SHA-256: `0D8366C53EA001869EE52E3175788CE9820952360C3B7E1706B3930E52C218E5`.
- The system splash uses a 288 dp icon canvas without an icon background, following the Android
  SplashScreen contract. The committed density assets are therefore 288, 432, 576, 864 and
  1152 px from `mdpi` through `xxxhdpi`.
- Each launch asset is generated directly from the canonical master in a single downsampling pass.
  The master occupies 75 % of the canvas; this keeps the visible mark within the platform's
  192 dp safe circle while retaining enough source pixels at every density.
- Density-specific `kwabor_brand_mark.png` files remain intentional for the launcher/auth mark.
  They must not be reused by the system splash.
- `tools/generate-brand-assets.ps1` regenerates the deterministic asset set and removes the
  obsolete no-density launch mark if present.
- `python -B tools/verify-brand-assets.py` locks the master hash, canvas sizes, visible geometry,
  derived hashes and Android/iOS wiring.

Perceptual launch evidence must cover at least API 30 (AndroidX backport), API 31 (native
SplashScreen) and the current target API before release. Record a cold start after a fresh install
and verify the sequence system mark → full wordmark → first intro frame without an enlarged
low-resolution intermediate.

.github/workflows/android-launch-evidence.yml automates that matrix on API 30, 31 and 36. For
each API, `tools/capture-android-launch-evidence.sh` performs fresh installs at `mdpi`, `xhdpi` and
`xxxhdpi`, records the fifteen-second cold start and produces a contact sheet plus device metadata.
The evidence APK uses only a reserved `.invalid` URL and a non-secret placeholder key; the capture
fails unless `MainActivity` stays resumed and reaches either the bundled intro or the configured
onboarding landing surface.
The workflow is called by CI only when a launch asset or its verification pipeline changes; its
artifacts are retained for 7 days. A human must still review every sequence before release.

## Google identity

- Source: Google Identity, [Sign in with Google branding guidelines](https://developers.google.com/identity/branding-guidelines), pre-approved Android + Web asset archive downloaded on 2026-07-22.
- Source archive SHA-256: `BA884069E12093B06BCFD776915081254A7C95094B80D50BE2C5DC6BAC1C1DA1`.
- `src/main/res/drawable-nodpi/google_g_logo.png` is the exact 80 × 80 px logo region from the official 4× light square asset (crop coordinates x=40, y=40, width=80, height=80), without resampling or color changes.
- Packaged logo SHA-256: `BFCFA3AC912941702A93536D6574CB44A7076D222B1E36AB0A45034CE09483C9`.
- The surrounding button is rendered natively so its French label, loading state, focus and 48 dp touch target remain accessible. Its surface, stroke and text colors follow the published Google light-theme values.

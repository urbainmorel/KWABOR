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

The first Activity creation keeps the system mark for at least 1,000 ms; Activity recreation does
not add that cold-start hold. API 33+ explicitly requests the icon-preferred system behavior.
After the platform splash is actually removed, the full wordmark must be drawn in a visible
window for at least 500 ms across distinct display frames. The intro player is attached behind
that wordmark, prepared paused at position zero, and may play only when the surface, continuity
barrier and foreground lifecycle are all ready.
On returning launches, the same full wordmark also covers any pending local decision about a
cached remote intro, so a slower cache check cannot expose the generic session-loading surface
between the system mark and a required intro.

.github/workflows/android-launch-evidence.yml automates that matrix on API 30, 31 and 36. For
each API, `tools/capture-android-launch-evidence.sh` resets application data for every `mdpi`,
`xhdpi` and `xxxhdpi` profile, asserts the effective display profile, arms a composited-display
capture on the HOME frame before launching Kwabor, and retains both that sequence and the raw
`screenrecord` stream through the configured onboarding surface. It resets application data again
before the continuous stream so that stream also covers a virgin first-launch wordmark → intro
transition; it does not reuse the onboarding completion persisted by the composited pass. The
composited sequence rejects inactive acquisition gaps above 4.5 seconds and validates every PNG
before encoding. The continuous recording spans at least 24 seconds of wall time. The workflow
publishes normalized review videos and contact sheets from both sources plus device metadata;
normalization never substitutes for review of the raw streams.
For the resume transition, Android must report `HOT` or `WARM`. The AOSP Activity Manager may
instead report `UNKNOWN (0)` only alongside its exact warning that the existing task was brought
to the front; the unchanged app PID, recorder growth, resumed activity and onboarding UI checks
remain mandatory and prevent that paired platform response from weakening the evidence gate.
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

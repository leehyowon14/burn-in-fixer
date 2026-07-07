# Changelog

## Unreleased

### Added
- Development log started for the reference-device color calibration work.
- Added target-device roles: `adjustment` for the device being corrected and `reference` for the uncorrected comparison display.
- Added a separate target-side white-balance layer so RGB white balance is applied on top of the existing burn-in correction instead of replacing it.
- Added scanner dual-device connection UI for adjustment/reference targets.
- Added a scanner white-balance calibration activity that compares the uncorrected reference display against the adjustment display with burn-in correction enabled, then uploads a separate RGB white-balance layer.

### Changed
- Reference-role target devices reject correction-map, white-balance, and overlay commands and force correction off when showing patterns.
- Reference-role pattern screens also force correction off for manual pattern previews and tap toggles.

### Verified
- Built `burn-in-fixed` with `:app:assembleDebug`.
- Built and tested `burn-in-camera` with `:app:testDebugUnitTest :app:assembleDebug`.

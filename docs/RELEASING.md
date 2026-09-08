# Releasing Ripple

This runbook is the source of truth for cutting the Android and iOS apps from
the same tagged commit. It deliberately does not automate store submission:
maintainers inspect and upload signed artifacts to the Play internal track and
TestFlight first.

## 1. Prepare one release commit

Prerequisites are Node 18+, JDK 17, and (for iOS) macOS with Xcode 15.4+ and
XcodeGen. A release also needs Google Play Console and Apple Developer/App Store
Connect access.

1. Start from a clean, reviewed `master` commit. Pick a SemVer tag such as
   `v1.0.1`.
2. Set Android's monotonically increasing `versionCode` and user-visible
   `versionName` in `android/app/build.gradle.kts`.
3. Set the matching `CFBundleShortVersionString` and a monotonically increasing
   `CFBundleVersion` in `ios/Ripple/Resources/Info.plist`.
4. Refresh the iOS English catalog and prove the wire vectors are unchanged:

   ```bash
   node tools/i18n/extract-ios.js
   node tools/protocol/test.js                 # must remain 16/16 for app-only work
   node tools/protocol/gen-vectors.js
   git diff --exit-code protocol/test-vectors.json \
     android/app/src/test/resources/test-vectors.json \
     ios/RippleTests/Resources/test-vectors.json
   ```

5. Merge the version PR only after the protocol, Android, and iOS CI jobs are
   green. On the resulting `master`, record the exact commit and tag it:

   ```bash
   git pull --ff-only origin master
   git status --short                         # must print nothing
   git tag -a v1.0.1 -m "Ripple v1.0.1"
   git push origin v1.0.1
   ```

The tag starts `.github/workflows/release.yml`. Keep Android and iOS artifacts,
store records, and release notes tied to that same tag/commit.

## 2. Android signing

### Create and protect the upload key (once)

Google Play App Signing should hold the app-signing key; the key below is the
separate upload key. Generate it offline and back it up in the project's secret
store. Never commit a keystore, password, or encoded keystore.

```bash
keytool -genkeypair -v \
  -keystore ripple-upload.jks \
  -alias ripple-upload \
  -keyalg RSA -keysize 4096 -validity 10000
keytool -list -v -keystore ripple-upload.jks -alias ripple-upload
```

The release workflow recognizes exactly these GitHub Actions repository
secrets:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Base64 of the binary JKS/PKCS12 file |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | `ripple-upload` (or the alias chosen above) |
| `ANDROID_KEY_PASSWORD` | Private-key password |

With GitHub CLI authenticated for this repository, set them without putting
passwords in a command line or tracked file:

```bash
base64 < ripple-upload.jks | tr -d '\n' | gh secret set ANDROID_KEYSTORE_BASE64
printf %s ripple-upload | gh secret set ANDROID_KEY_ALIAS
gh secret set ANDROID_KEYSTORE_PASSWORD     # paste at the prompt
gh secret set ANDROID_KEY_PASSWORD          # paste at the prompt
```

All four present means CI signs the AAB. None present means CI intentionally
publishes an **unsigned** AAB plus `SIGNING-NOTICE.txt` and this runbook. A
partial configuration fails rather than silently producing the wrong artifact.
Do not upload an artifact whose filename or notice says `unsigned`; configure
all secrets and rerun the workflow from the tag instead.

To make the same signed bundle locally, use environment variables so secrets do
not enter Gradle files:

```bash
cd android
export RIPPLE_KEYSTORE_PATH=/absolute/path/ripple-upload.jks
read -rsp 'Keystore password: ' RIPPLE_KEYSTORE_PASSWORD; export RIPPLE_KEYSTORE_PASSWORD; echo
export RIPPLE_KEY_ALIAS=ripple-upload
read -rsp 'Key password: ' RIPPLE_KEY_PASSWORD; export RIPPLE_KEY_PASSWORD; echo
gradle wrapper --gradle-version 8.9 --no-daemon    # only if gradlew is absent
./gradlew clean bundleRelease --no-daemon
jarsigner -verify app/build/outputs/bundle/release/app-release.aab
unset RIPPLE_KEYSTORE_PASSWORD RIPPLE_KEY_PASSWORD
```

### CI artifact and no-internet check

For both tag and manual runs, the workflow:

- runs `bundleRelease` with JDK 17;
- fails if any merged release manifest declares
  `android.permission.INTERNET`;
- verifies a configured signature and uploads the AAB, R8 `mapping.txt` when
  present, signing notice, and this runbook as a workflow artifact;
- attaches tag builds to a draft GitHub release (or an existing release for the
  tag).

Before store upload, inspect the workflow run and certificate. The source
manifest's explicit no-internet invariant can also be checked quickly with:

```bash
! grep -R 'android.permission.INTERNET' android/app/src/main/AndroidManifest.xml
```

The workflow checks the merged manifest as the authoritative result, so a
future dependency cannot smuggle the permission in unnoticed.

### Play internal track

1. In Play Console, create/select package `app.ripple.mesh`, enroll in Play App
   Signing, and register the upload certificate from the key above.
2. Complete App content, privacy/data-safety, Bluetooth/nearby-device, optional
   location, encryption, and emergency-feature declarations truthfully. Ripple
   has no server transport; SOS location is attached only after the user's
   opt-in. Store forms and a privacy policy still need maintainer review.
3. Open **Testing → Internal testing**, create a release, and upload the
   `signed.aab` from the tag workflow. Upload `mapping.txt` for de-obfuscation.
4. Confirm Play reports the expected version name/code and upload certificate.
   Add internal testers, review warnings, roll out, and install from the Play
   link on at least two physical BLE-capable phones.
5. Run the Tier 1 field checklist and identity backup/restore acceptance before
   promoting beyond internal testing. Every later Play upload must increment
   `versionCode`.

### F-Droid submission

English store metadata is in `fastlane/metadata/android/en-US/` (`title.txt`,
`short_description.txt`, `full_description.txt`, and version-code changelogs).
Keep it synchronized with README claims. Submit/update the F-Droid build recipe
against the immutable release tag and package `app.ripple.mesh`; F-Droid should
build from source, not consume the Play-signed AAB.

During review, point to the manifest and the release workflow's merged-manifest
check as evidence that the app requests no `INTERNET` permission. Re-check that
new dependencies are reproducibly obtainable by F-Droid and that no proprietary
service SDK has entered the app.

## 3. iOS archive, export, and TestFlight

### Signing setup

1. Use an Apple Developer team that can publish `app.ripple.mesh`. Create the
   matching App Store Connect app record and accept current agreements.
2. In Xcode, sign in with that account. Automatic signing may create the Apple
   Distribution certificate and App Store provisioning profile; the team must
   have permission to do so.
3. From the tagged checkout, generate the project and run tests:

   ```bash
   cd ios
   xcodegen generate
   xcodebuild test -scheme Ripple -project Ripple.xcodeproj \
     -destination 'platform=iOS Simulator,name=iPhone 16' \
     CODE_SIGNING_ALLOWED=NO DEVELOPMENT_TEAM=''
   ```

The generated project is disposable; `ios/project.yml` is the tracked source of
truth. Its shared `Ripple` scheme explicitly archives the Release configuration.

### Archive and export

Replace `TEAMID` and keep the archive with the tag's release records:

```bash
cd ios
rm -rf build/Ripple.xcarchive build/export
xcodegen generate
xcodebuild archive \
  -scheme Ripple \
  -project Ripple.xcodeproj \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath "$PWD/build/Ripple.xcarchive" \
  DEVELOPMENT_TEAM=TEAMID \
  -allowProvisioningUpdates

xcodebuild -exportArchive \
  -archivePath "$PWD/build/Ripple.xcarchive" \
  -exportPath "$PWD/build/export" \
  -exportOptionsPlist ExportOptions-AppStore.plist \
  -allowProvisioningUpdates
```

Validate the archive in Xcode Organizer, then upload the exported IPA with
Apple's Transporter app (or choose **Distribute App → App Store Connect** in
Organizer). In App Store Connect, wait for processing, answer export-compliance
questions as below, add the build to an internal TestFlight group, and install it
on at least two physical iPhones. Bluetooth mesh behavior cannot be accepted in
the simulator.

### Encryption export compliance decision

`ITSAppUsesNonExemptEncryption` is deliberately set to **`true`** in
`Info.plist`. This is the conservative declaration: Ripple encrypts direct
messages and identity backups using standard ECDH/HKDF/AES-GCM and signs with
ECDSA. Using Apple's CryptoKit/CommonCrypto implementations does not by itself
make encrypted messaging exempt, and “no internet” does not remove export
obligations.

For every App Store Connect submission:

1. declare that the app uses encryption and describe the standard, publicly
   documented algorithms above;
2. determine with the organization's export-compliance owner whether the build
   qualifies for mass-market treatment and whether an ENC self-classification
   report, CCATS, or other documentation is required for the maintainer's
   country and distribution; upload the requested document/reference;
3. retain the App Store Connect answers and any annual U.S. self-classification
   filing with the release records.

Do not flip the plist value to `false` merely to bypass the questionnaire. If
qualified counsel or written Apple/BIS guidance supports a different answer,
document that decision in a reviewed PR before changing it. This runbook records
the project's technical decision, not legal advice.

## 4. Final release record

For both stores, record:

- tag and full commit SHA;
- workflow run URL and Android signing certificate fingerprint;
- Android version code/name and iOS build/short version;
- Play internal release and TestFlight build links;
- export-compliance answer/document reference;
- physical-device acceptance issues and any known limitations.

Diagnostics exports now include a privacy-bounded previous-crash record: Android
replays a truncated stack with no exception message, while iOS includes the
truncated exception name/reason after the next launch. Neither handler reads or
stores message records, keys, or full node IDs.

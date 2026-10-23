# Version releases

- From 0.3.0 onward, every app version bump must include a signed APK published
  to this repository's GitHub Release or pre-release. A commit alone is not a
  completed version update.
- Increment both `versionName` and `versionCode` in `app/build.gradle.kts`.
- Use Angular-style commit messages. Exclude unrelated working-tree changes.
- Build with `tools/Build-Release.ps1 -SigningDirectory <local-directory>`.
  Obtain the signing location from the user or private local configuration.
  Never commit or publish personal filesystem paths, private keys, passwords,
  or credential files. Instructions to use local files do not authorize
  publishing their locations or contents.
- Run the relevant checks, verify APK version and signing certificate, and attach
  the signed APK and SHA-256 checksum to the release tagged `v<version>`.
- Push the version commit and tag before publishing. Use the exact version
  commit as the release target. Stable version names use a normal Release;
  alpha/beta/rc versions use a pre-release unless the user specifies otherwise.
- Keep release notes focused on user-visible changes. Confirm the published tag,
  assets, and checksums and provide the release URL.

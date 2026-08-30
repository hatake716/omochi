# Third-party notices

Omochi application code is licensed under Apache License 2.0. The following third-party components are
distributed with, or downloaded by, Omochi under their own licenses.

## Embedded in the APK

| Component | Purpose | License | Corresponding material |
|---|---|---|---|
| PRoot (Termux Android build) | Rootless Linux process translation | GPL-2.0 | `app/src/main/assets/legal/sources/proot-v5.1.107.92.zip` |
| PRoot loader | Guest executable loader | GPL-2.0 | Same PRoot source and included build script |
| libandroid-shmem | Android shared-memory compatibility | BSD-3-Clause | `app/src/main/assets/legal/sources/libandroid-shmem-v0.7.tar.gz.source` |
| Samba talloc | PRoot runtime dependency | LGPL-3.0-or-later | `app/src/main/assets/legal/sources/talloc-2.4.3.tar.gz.source` |
| Apache Commons Compress | Tar/gzip extraction | Apache-2.0 | Upstream source; notice included in APK |
| AndroidX / Jetpack Compose | Android application UI and lifecycle | Apache-2.0 | Resolved from Google Maven at build time |

License texts, source snapshots, recipes, and SHA-256 inventory are under `app/src/main/assets/legal/` and are
packaged into every APK. The Android/Bionic PRoot bundle was reused from the Apache-2.0 CCFA project while
preserving the original GPL/LGPL/BSD obligations and corresponding-source material.

## Downloaded after explicit setup

### Ubuntu Base 24.04.4 ARM64

Omochi downloads Ubuntu Base from the official Ubuntu image host. Ubuntu packages contain their copyright
and license records under `/usr/share/doc` after extraction/installation. The archive is verified against a
pinned official SHA-256 before use.

### code-server 4.133.0 / Code 1.133.0

Omochi downloads the official `coder/code-server` Linux ARM64 standalone archive. The archive contains its
own `LICENSE` and `ThirdPartyNotices.txt`, which remain under `/opt/omochi/code-server-4.133.0-linux-arm64/`.
The archive is not repackaged into the Omochi APK and is verified with the official GitHub release-asset
SHA-256.

## Trademarks and affiliation

Omochi is not affiliated with or endorsed by Microsoft Corporation, the Visual Studio Code project, or
Coder Technologies, Inc. Visual Studio Code, Code - OSS, code-server, Android, Ubuntu, and other product names
remain the property of their respective owners and are used only to identify compatibility or upstream
components.

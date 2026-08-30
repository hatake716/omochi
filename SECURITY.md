# Security policy

## Supported versions

Omochi is currently pre-release software. Security fixes are applied to the latest `main` branch until the
first tagged release establishes a longer support policy.

## Reporting a vulnerability

Do not publish credentials, private repository content, or an exploit containing user data in a public issue.
Use GitHub's private vulnerability reporting feature for `hatake716/omochi` when available. Include:

- Omochi commit/version and Android version;
- whether the issue crosses the Android app UID or loopback boundary;
- a minimal reproduction with disposable data;
- relevant log lines with tokens, remote URLs, usernames, and paths redacted.

## Security model

- code-server binds only to a dynamically selected port on `127.0.0.1`;
- the workbench requires a 256-bit app-private random password even on loopback;
- Android framework/WebView cleartext networking is allowed only for loopback in Network Security Config;
- downloaded Ubuntu and code-server archives have pinned SHA-256 values;
- PRoot does not grant Android root privileges;
- workspace files stay in app-private storage unless the user explicitly imports/exports through SAF;
- external Extension Gallery access is disabled and not part of the supported product;
- no API key, Git credential, signing key, or user token belongs in this repository.

The app does not currently isolate untrusted project tasks from the rest of Omochi's private Linux rootfs.
Opening a repository and running its tasks or terminal commands grants that code the same app-private Linux
access as the IDE. Treat projects and shell commands as executable code.

Guest Git, apt, terminals, tasks, and debugged programs can make external network connections through the
app's `INTERNET` permission; Android Network Security Config does not mediate those native guest sockets.

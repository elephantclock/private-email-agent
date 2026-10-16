Test-only self-signed certificates (password `changeit`). Not secrets.

- `localhost.p12`: SAN `localhost`, `127.0.0.1`. GreenMail's TLS servers use it (set via `greenmail.tls.keystore.file`).
- `other-host.p12`: SAN `mail.example.com` only, for hostname-mismatch tests.

Regenerate with `keytool -genkeypair ... -validity 36500 -ext SAN=...` (see git history of T11).

# Local secrets

Files here are mounted read-only into the sandbox container at `/secrets` by `docker-compose.yml`, the way
Cloud Run mounts a secret. Everything in this folder except this file is git-ignored.

To let the local sandbox send training records to the AEAT test environment, copy the VERI*FACTU certificate
here (for example `verifactu.pfx`) and set in `.env`:

```
SANDBOX_VERIFACTU_CERTIFICATE_PATH=/secrets/verifactu.pfx
VERIFACTU_CERTIFICATE_PASSWORD=...
VERIFACTU_ISSUERS=B09767898
```

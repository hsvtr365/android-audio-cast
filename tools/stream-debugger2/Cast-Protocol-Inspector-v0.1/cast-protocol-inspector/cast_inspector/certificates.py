"""Generate development-only RSA/TLS certificate, never Google device credentials."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from pathlib import Path


def ensure_local_cert(folder: Path) -> tuple[Path, Path]:
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID

    folder.mkdir(parents=True, exist_ok=True)
    cert_file, key_file = folder / 'debug-cert.pem', folder / 'debug-key.pem'
    if cert_file.exists() and key_file.exists():
        return cert_file, key_file
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    now = datetime.now(timezone.utc)
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'Cast Debug Receiver - UNTRUSTED')])
    cert = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject)
            .public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now - timedelta(minutes=1)).not_valid_after(now + timedelta(days=30))
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .sign(key, hashes.SHA256()))
    key_file.write_bytes(key.private_bytes(serialization.Encoding.PEM,
                                            serialization.PrivateFormat.TraditionalOpenSSL,
                                            serialization.NoEncryption()))
    cert_file.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    try:
        key_file.chmod(0o600)
    except OSError:
        pass
    return cert_file, key_file

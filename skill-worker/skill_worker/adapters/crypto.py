"""AES-256-GCM 凭据解密：与 gateway CryptoService（Java）同构。

密文格式：base64(12B IV + ciphertext + 16B tag)——Java GCM doFinal 返回 ct||tag，
Python cryptography AESGCM.encrypt 同构，天然互通（测试内置固定向量锁定格式）。
"""

from __future__ import annotations

import base64


def decrypt(crypto_key_base64: str, encoded_cipher: str) -> str:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    key = base64.b64decode(crypto_key_base64)
    if len(key) != 32:
        raise ValueError("AES key 必须为 32 字节（base64 编码后传入）")
    blob = base64.b64decode(encoded_cipher)
    iv, ciphertext_with_tag = blob[:12], blob[12:]
    plain = AESGCM(key).decrypt(iv, ciphertext_with_tag, None)
    return plain.decode()


def encrypt(crypto_key_base64: str, plain: str) -> str:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    import os

    key = base64.b64decode(crypto_key_base64)
    iv = os.urandom(12)
    ciphertext_with_tag = AESGCM(key).encrypt(iv, plain.encode(), None)
    return base64.b64encode(iv + ciphertext_with_tag).decode()

"""AES-GCM 凭据加解密测试：与 gateway CryptoService（Java）跨语言同构。

固定向量由 Java 侧实际生成（jshell + gateway target/classes），锁定 wire 格式：
base64(12B IV + ciphertext + 16B tag)。
"""

from __future__ import annotations

import base64

import pytest

from skill_worker.adapters.crypto import decrypt, encrypt

KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="  # 32 字节全零（本地开发默认值）

# jshell> new com.skill.platform.gateway.security.CryptoService(KEY).encrypt("sk-secret-cross-lang")
JAVA_VECTOR = "K7vw84RvHi9dM1ErsrUe434Q+IZqp8b5Y9R/sCNkRWHqwrbA0f66n2YA9gF5lhnC"


def test_decrypt_java_generated_cipher():
    """worker 解密 gateway 加密的 AppSecret（回调 HMAC 签名的关键链路）"""
    assert decrypt(KEY, JAVA_VECTOR) == "sk-secret-cross-lang"


def test_encrypt_decrypt_roundtrip_with_random_iv():
    cipher = encrypt(KEY, "sk-secret-worker-side")
    assert cipher != encrypt(KEY, "sk-secret-worker-side")  # 随机 IV
    assert decrypt(KEY, cipher) == "sk-secret-worker-side"


def test_invalid_key_rejected():
    with pytest.raises(ValueError):
        decrypt(base64.b64encode(b"short-key").decode(), JAVA_VECTOR)


def test_tampered_cipher_rejected():
    blob = bytearray(base64.b64decode(JAVA_VECTOR))
    blob[-1] ^= 0x01
    with pytest.raises(Exception):
        decrypt(KEY, base64.b64encode(bytes(blob)).decode())

#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generates known-answer vectors for the AirSense 11 protocol tests.

This is an independent implementation (Python big integers, hashlib, zlib) of
the formulas the Kotlin code implements, so agreement between the two is
evidence that neither has a serialisation or padding mistake. It follows the
formulas documented in as11/src/main/kotlin/.../Srp6a.kt.

Usage: python3 tools/gen_protocol_vectors.py > as11/src/test/resources/protocol_vectors.json
"""
import hashlib, hmac, json, struct, subprocess, zlib

N = int(
    "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050"
    "A37329CBB4A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50"
    "E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B8"
    "55F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773B"
    "CA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748"
    "544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6"
    "AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB6"
    "94B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73", 16)
G = 2
PAD = 256

def pad(n): return n.to_bytes(PAD, "big")
def H(*parts):
    h = hashlib.sha256()
    for p in parts: h.update(p)
    return h.digest()

def srp_case(name, a_bytes, b_bytes, salt, passkey):
    a = int.from_bytes(a_bytes, "big"); b = int.from_bytes(b_bytes, "big")
    A = pow(G, a, N)
    k = int.from_bytes(H(pad(N), pad(G)), "big")
    x = int.from_bytes(H(salt, H(passkey.encode("ascii"))), "big")
    v = pow(G, x, N)
    B = (k * v + pow(G, b, N)) % N
    u = int.from_bytes(H(pad(A), pad(B)), "big")
    S_client = pow((B - k * pow(G, x, N)) % N, a + u * x, N)
    S_server = pow(A * pow(v, u, N) % N, b, N)
    assert S_client == S_server
    K = H(pad(S_client))
    hn, hg = H(pad(N)), H(pad(G))
    M1 = H(bytes(p ^ q for p, q in zip(hn, hg)), salt, pad(A), pad(B), K)
    M2 = H(pad(A), M1, K)
    return {
        "name": name, "a": a_bytes.hex(), "passkey": passkey, "salt": salt.hex(),
        "A": pad(A).hex(), "B": pad(B).hex(), "B_unpadded": format(B, "x"),
        "K": K.hex(), "M1": M1.hex(), "M2": M2.hex(),
        "A_leading_zero_bytes": PAD - (A.bit_length() + 7) // 8,
        "B_leading_zero_bytes": PAD - (B.bit_length() + 7) // 8,
        "S_leading_zero_bytes": PAD - (S_client.bit_length() + 7) // 8,
    }

def find_case(name, want, passkey):
    """Searches deterministic inputs until A, B or S has a leading zero byte."""
    i = 0
    while True:
        a = H(b"paprelay-a", name.encode(), struct.pack(">I", i))
        b = H(b"paprelay-b", name.encode(), struct.pack(">I", i))
        salt = H(b"paprelay-salt", name.encode(), struct.pack(">I", i))[:16]
        c = srp_case(name, a, b, salt, passkey)
        if want is None or c[want] > 0: return c
        i += 1

def fig(channel, payload):
    header = struct.pack("<HHI", channel, len(payload), zlib.crc32(payload) & 0xFFFFFFFF)
    return bytes.fromhex("BEBAFECA") + header + struct.pack("<I", zlib.crc32(header) & 0xFFFFFFFF) + payload

K = H(b"paprelay-test-pairing-key")
challenge = H(b"paprelay-test-challenge")
nonce = H(b"paprelay-test-nonce")
out = {
    "srp": [
        find_case("plain", None, "0420"),
        find_case("leading-zero-passkey", None, "0007"),
        find_case("A-leading-zero", "A_leading_zero_bytes", "1234"),
        find_case("B-leading-zero", "B_leading_zero_bytes", "5678"),
        find_case("S-leading-zero", "S_leading_zero_bytes", "9012"),
    ],
    "fig": [
        {"channel": 0x0393, "payload": b'{"id":1}'.hex(), "frame": fig(0x0393, b'{"id":1}').hex()},
        {"channel": 0x0397, "payload": "", "frame": fig(0x0397, b"").hex()},
        {"channel": 0x0396, "payload": bytes(range(256)).hex(), "frame": fig(0x0396, bytes(range(256))).hex()},
    ],
    "session": {
        "pairingKey": K.hex(), "challenge": challenge.hex(), "nonce": nonce.hex(),
        "response": hmac.new(K, challenge, hashlib.sha256).digest().hex(),
        "sessionKey": H(K, nonce).hex(),
    },
}

def aes_case(message):
    """AES-256-CBC through the openssl CLI: independent of the JVM provider."""
    key = H(K, nonce); iv = H(b"paprelay-test-iv", message)[:16]
    framed = struct.pack("<H", len(message)) + message
    framed += b"\0" * (-len(framed) % 16)
    ct = subprocess.run(["openssl", "enc", "-aes-256-cbc", "-nopad", "-K", key.hex(), "-iv", iv.hex()],
                        input=framed, capture_output=True, check=True).stdout
    return {"iv": iv.hex(), "message": message.hex(), "payload": (iv + ct).hex()}

out["aes"] = [aes_case(m) for m in (
    b'{"id":20,"jsonrpc":"1.0","method":"GetDateTime"}',
    b"x" * 14,   # exactly fills one block with the length prefix
    b"x" * 15,   # spills one byte into a second block
    b"",
)]
print(json.dumps(out, indent=1))

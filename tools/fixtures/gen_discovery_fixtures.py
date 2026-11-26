#!/usr/bin/env python3
"""Generate UDP discovery broadcast fixtures for the :tuya module (Discovery).

Packets are built the way Tuya devices broadcast them (see tinytuya scanner.py /
core/udp_helper.py) and each one is validated with tinytuya's own decrypt_udp().

- 6666: 55AA frame, cmd 0, retcode 0, payload = plaintext JSON (3.1 style; tinytuya's
        decrypt_udp() returns payloads starting with "{" as-is), CRC32 trailer.
- 6667: 55AA frame, cmd 0, retcode 0, payload = AES-128-ECB(JSON) keyed with the UDP key,
        CRC32 trailer.
- 7000: 6699 frame, cmd 0x13 (UDP_NEW), AES-128-GCM keyed with the UDP key, no retcode,
        payload padded with NUL bytes like some real devices/apps do.

Only FAKE ids / private IPs. Deterministic (fixed seq, fixed GCM IV).

Run with the probe venv (tinytuya installed):
    tools/probe/.venv/bin/python tools/fixtures/gen_discovery_fixtures.py
"""

import json
from pathlib import Path

from tinytuya.core import header as H
from tinytuya.core.crypto_helper import AESCipher
from tinytuya.core.message_helper import TuyaMessage, pack_message
from tinytuya.core.udp_helper import decrypt_udp, udpkey

FAKE_DEVICE_ID = "fakedeviceid0000000000"
FAKE_PRODUCT_KEY = "fakeproductkey00"
OUT_DIR = Path(__file__).resolve().parents[2] / "tuya" / "src" / "test" / "resources" / "fixtures"


def _compact(obj):
    return json.dumps(obj, separators=(",", ":"))


def _info(ip, version, **extra):
    d = {"ip": ip, "gwId": FAKE_DEVICE_ID, "active": 2, "ability": 0, "mode": 0,
         "encrypt": True, "productKey": FAKE_PRODUCT_KEY, "version": version}
    d.update(extra)
    return d


def _plain_frame(info):
    payload = (0).to_bytes(4, "big") + _compact(info).encode()
    return pack_message(TuyaMessage(0, 0, 0, payload, 0, True, H.PREFIX_55AA_VALUE, None))


def _expected(info):
    return {"ip": info["ip"], "deviceId": info["gwId"], "productKey": info.get("productKey"),
            "version": info.get("version")}


def _case(name, port, packet, info, parses):
    got = json.loads(decrypt_udp(packet).rstrip("\x00"))
    assert got == info, (got, info)  # tinytuya reads it back identically
    return {"name": name, "port": port, "packet_hex": packet.hex(),
            "expected": _expected(info) if parses else None}


def gen_discovery():
    cases = []

    # 6666: framed plaintext. 3.1 is not supported by the app -> parse() returns null.
    info31 = _info("192.168.1.50", "3.1")
    cases.append(_case("6666 plaintext 3.1 (unsupported)", 6666, _plain_frame(info31), info31, False))
    info33p = _info("192.168.1.51", "3.3")
    cases.append(_case("6666 plaintext 3.3", 6666, _plain_frame(info33p), info33p, True))

    # 6667: 3.3 framing, ECB with the UDP key, retcode 0, cmd 0.
    info33 = _info("192.168.1.52", "3.3")
    body = AESCipher(udpkey).encrypt(_compact(info33).encode(), False)
    msg = TuyaMessage(0, 0, 0, (0).to_bytes(4, "big") + body, 0, True, H.PREFIX_55AA_VALUE, None)
    cases.append(_case("6667 ECB 3.3", 6667, pack_message(msg), info33, True))

    # 7000: 3.5 framing, GCM with the UDP key, cmd UDP_NEW, fixed IV, NUL padding.
    info35 = _info("192.168.1.53", "3.5")
    raw = _compact(info35).encode() + b"\x00\x00\x00"
    iv = bytes.fromhex("303132333435363738393a3b")
    msg = TuyaMessage(0, 0x13, None, raw, 0, True, H.PREFIX_6699_VALUE, iv)
    cases.append(_case("7000 GCM 3.5", 7000, pack_message(msg, hmac_key=udpkey), info35, True))

    # 7000: what the app broadcasts to request device info; has no gwId -> not a device.
    app = {"from": "app", "ip": "192.168.1.2"}
    msg = TuyaMessage(0, 0x25, None, _compact(app).encode(), 0, True, H.PREFIX_6699_VALUE, iv)
    packet = pack_message(msg, hmac_key=udpkey)
    assert json.loads(decrypt_udp(packet)) == app
    cases.append({"name": "7000 app request (no gwId)", "port": 7000, "packet_hex": packet.hex(),
                  "expected": None})

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    (OUT_DIR / "discovery.json").write_text(json.dumps(cases, indent=2) + "\n")
    print("wrote", OUT_DIR / "discovery.json")


if __name__ == "__main__":
    gen_discovery()

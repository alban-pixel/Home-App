#!/usr/bin/env python3
"""Generate Tuya LAN protocol test fixtures for the :tuya module.

Uses tinytuya's own framing (pack_message / unpack_message / AESCipher) so the
Kotlin codec is checked against a reference implementation. Output is
deterministic: fixed seq numbers, fixed timestamps, fixed GCM IVs.

Only FAKE credentials are used. Never put a real local key or device ID here.

Run with the probe venv (tinytuya installed):
    tools/probe/.venv/bin/python tools/fixtures/gen_fixtures.py

Each fixture file has its own gen_* function; add new ones next to gen_messages.
"""

import json
import struct
from pathlib import Path

from tinytuya.core import header as H
from tinytuya.core import command_types as CT
from tinytuya.core.crypto_helper import AESCipher
from tinytuya.core.message_helper import TuyaMessage, pack_message, unpack_message

FAKE_KEY = b"0123456789abcdef"
FAKE_DEVICE_ID = "fakedeviceid0000000000"
FIXED_T = "1700000000"

OUT_DIR = Path(__file__).resolve().parents[2] / "tuya" / "src" / "test" / "resources" / "fixtures"

VERSION_HEADERS = {
    "3.3": H.PROTOCOL_33_HEADER,
    "3.4": H.PROTOCOL_34_HEADER,
    "3.5": H.PROTOCOL_35_HEADER,
}


def _compact(obj):
    return json.dumps(obj, separators=(",", ":"))


def _frame(version, seq, cmd, payload, key, retcode=None, iv=None):
    """Frame a message exactly like tinytuya's XenonDevice._encode_message.

    retcode != None builds a device->client frame (retcode right after the
    header for 55AA, first 4 bytes of the plaintext for 6699).
    """
    add_header = cmd not in H.NO_PROTOCOL_HEADER_CMDS
    vh = VERSION_HEADERS[version] if add_header else b""

    if version == "3.5":
        msg = TuyaMessage(seq, cmd, retcode, vh + payload, 0, True, H.PREFIX_6699_VALUE, iv)
        return pack_message(msg, hmac_key=key)

    cipher = AESCipher(key)
    if version == "3.4":
        # 3.4: version header is encrypted together with the payload, HMAC trailer
        body = cipher.encrypt(vh + payload, False)
        hmac_key = key
    else:
        # 3.3: version header is prepended to the ciphertext, CRC32 trailer
        body = vh + cipher.encrypt(payload, False)
        hmac_key = None

    rc = b"" if retcode is None else struct.pack(H.MESSAGE_RETCODE_FMT, retcode)
    msg = TuyaMessage(seq, cmd, 0, rc + body, 0, True, H.PREFIX_55AA_VALUE, None)
    return pack_message(msg, hmac_key=hmac_key)


def _check(version, data, seq, cmd, payload, key, retcode):
    """Decode with tinytuya to make sure the fixture is valid."""
    hmac_key = None if version == "3.3" else key
    msg = unpack_message(data, hmac_key=hmac_key, no_retcode=retcode is None)
    assert msg.crc_good, "bad check value"
    assert (msg.seqno, msg.cmd) == (seq, cmd)
    if retcode is not None:
        assert msg.retcode == retcode
    vh = VERSION_HEADERS[version]
    body = msg.payload
    if version == "3.3":
        if body.startswith(vh):
            body = body[len(vh):]
        body = AESCipher(key).decrypt(body, False, decode_text=False)
    elif version == "3.4":
        body = AESCipher(key).decrypt(body, False, decode_text=False)
    if version != "3.3" and body.startswith(vh):
        body = body[len(vh):]
    assert body == payload, (body, payload)


def _case(name, version, seq, cmd, payload_obj, retcode=None, iv=None):
    payload = _compact(payload_obj).encode()
    data = _frame(version, seq, cmd, payload, FAKE_KEY, retcode=retcode, iv=iv)
    _check(version, data, seq, cmd, payload, FAKE_KEY, retcode)
    case = {
        "name": name,
        "version": version,
        "seq": seq,
        "cmd": cmd,
        "key_hex": FAKE_KEY.hex(),
        "payload_utf8": payload.decode(),
        "bytes_hex": data.hex(),
    }
    if retcode is not None:
        case["retcode"] = retcode
    if iv is not None:
        case["iv_hex"] = iv.hex()
    return case


def gen_messages():
    """messages.json: framing of client->device and device->client messages."""
    dev = FAKE_DEVICE_ID
    query33 = {"gwId": dev, "devId": dev, "uid": dev, "t": FIXED_T}
    control33 = {"devId": dev, "uid": dev, "t": FIXED_T, "dps": {"201": "fake-ir-code-on"}}
    control_new = {"protocol": 5, "t": int(FIXED_T), "data": {"dps": {"201": "fake-ir-code-on"}}}
    status = {"dps": {"201": "fake-ir-code-on"}, "t": int(FIXED_T)}

    cases = [
        _case("3.3 DP_QUERY client->device", "3.3", 1, CT.DP_QUERY, query33),
        _case("3.3 CONTROL client->device", "3.3", 2, CT.CONTROL, control33),
        _case("3.3 STATUS device->client", "3.3", 3, CT.STATUS, status, retcode=0),
        _case("3.4 DP_QUERY_NEW client->device", "3.4", 1, CT.DP_QUERY_NEW, {}),
        _case("3.4 CONTROL_NEW client->device", "3.4", 2, CT.CONTROL_NEW, control_new),
        _case("3.4 STATUS device->client", "3.4", 3, CT.STATUS, status, retcode=0),
        _case("3.5 DP_QUERY_NEW client->device", "3.5", 1, CT.DP_QUERY_NEW, {},
              iv=bytes.fromhex("000102030405060708090a0b")),
        _case("3.5 CONTROL_NEW client->device", "3.5", 2, CT.CONTROL_NEW, control_new,
              iv=bytes.fromhex("101112131415161718191a1b")),
        _case("3.5 STATUS device->client", "3.5", 3, CT.STATUS, status, retcode=0,
              iv=bytes.fromhex("202122232425262728292a2b")),
    ]
    _write("messages.json", cases)


def _write(name, data):
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    (OUT_DIR / name).write_text(json.dumps(data, indent=2) + "\n")
    print("wrote", OUT_DIR / name)


if __name__ == "__main__":
    gen_messages()

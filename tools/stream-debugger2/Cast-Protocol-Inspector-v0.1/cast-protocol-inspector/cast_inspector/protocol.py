"""Minimal Cast V2 Protobuf wire codec (CastMessage only).

CastMessage fields follow cast_channel.proto; unknown fields are ignored.
No .proto compiler or compiled protobuf module is needed for inspection.
"""
from __future__ import annotations

from dataclasses import dataclass

MAX_FRAME_BYTES = 1024 * 1024


class ProtocolError(ValueError):
    pass


def varint(value: int) -> bytes:
    if value < 0:
        raise ProtocolError("negative varint")
    out = bytearray()
    while value > 127:
        out.append((value & 0x7F) | 0x80)
        value >>= 7
    out.append(value)
    return bytes(out)


def read_varint(buf: bytes, pos: int) -> tuple[int, int]:
    number = 0
    for shift in range(0, 70, 7):
        if pos >= len(buf):
            raise ProtocolError("truncated varint")
        b = buf[pos]
        pos += 1
        number |= (b & 0x7F) << shift
        if b < 128:
            return number, pos
    raise ProtocolError("varint too long")


def field_varint(tag: int, value: int) -> bytes:
    return varint((tag << 3) | 0) + varint(value)


def field_bytes(tag: int, value: bytes) -> bytes:
    return varint((tag << 3) | 2) + varint(len(value)) + value


def decode_fields(raw: bytes) -> dict[int, list[int | bytes]]:
    pos = 0
    result: dict[int, list[int | bytes]] = {}
    while pos < len(raw):
        tag, pos = read_varint(raw, pos)
        field = tag >> 3
        wire = tag & 7
        if field == 0:
            raise ProtocolError("invalid field 0")
        if wire == 0:
            val, pos = read_varint(raw, pos)
        elif wire == 2:
            length, pos = read_varint(raw, pos)
            if length > MAX_FRAME_BYTES or pos + length > len(raw):
                raise ProtocolError("invalid length-delimited field")
            val = raw[pos:pos + length]
            pos += length
        elif wire == 1:
            if pos + 8 > len(raw):
                raise ProtocolError("truncated fixed64")
            val = raw[pos:pos + 8]
            pos += 8
        elif wire == 5:
            if pos + 4 > len(raw):
                raise ProtocolError("truncated fixed32")
            val = raw[pos:pos + 4]
            pos += 4
        else:
            raise ProtocolError(f"unsupported wire type {wire}")
        result.setdefault(field, []).append(val)
    return result


def one(fields: dict[int, list[int | bytes]], field: int, expected: type):
    values = fields.get(field, [])
    if not values:
        raise ProtocolError(f"missing required CastMessage field {field}")
    val = values[-1]
    if not isinstance(val, expected):
        raise ProtocolError(f"wrong wire type for field {field}")
    return val


@dataclass(frozen=True)
class CastMessage:
    source_id: str
    destination_id: str
    namespace: str
    payload_utf8: str | None = None
    payload_binary: bytes | None = None

    def encode(self) -> bytes:
        binary = self.payload_binary is not None
        parts = [field_varint(1, 0),  # CASTV2_1_0
                 field_bytes(2, self.source_id.encode()),
                 field_bytes(3, self.destination_id.encode()),
                 field_bytes(4, self.namespace.encode()),
                 field_varint(5, 1 if binary else 0)]
        if binary:
            parts.append(field_bytes(7, self.payload_binary or b""))
        else:
            parts.append(field_bytes(6, (self.payload_utf8 or "").encode()))
        return b"".join(parts)

    @staticmethod
    def decode(raw: bytes) -> "CastMessage":
        fields = decode_fields(raw)
        version = one(fields, 1, int)
        if version != 0:
            raise ProtocolError(f"unsupported Cast protocol version {version}")
        payload_type = one(fields, 5, int)
        if payload_type not in (0, 1):
            raise ProtocolError(f"invalid payload type {payload_type}")
        try:
            source = one(fields, 2, bytes).decode("utf-8")
            target = one(fields, 3, bytes).decode("utf-8")
            namespace = one(fields, 4, bytes).decode("utf-8")
            if payload_type == 1:
                payload = one(fields, 7, bytes)
                if not isinstance(payload, bytes):
                    raise ProtocolError("invalid binary payload")
                return CastMessage(source, target, namespace, payload_binary=payload)
            payload = one(fields, 6, bytes)
            if not isinstance(payload, bytes):
                raise ProtocolError("invalid utf8 payload")
            return CastMessage(source, target, namespace, payload_utf8=payload.decode("utf-8"))
        except UnicodeDecodeError as exc:
            raise ProtocolError("invalid UTF-8") from exc


def pack_frame(message: CastMessage) -> bytes:
    payload = message.encode()
    if len(payload) > MAX_FRAME_BYTES:
        raise ProtocolError("frame too large")
    return len(payload).to_bytes(4, 'big') + payload

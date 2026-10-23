"""Advertise a test Cast receiver on LAN using mDNS."""
from __future__ import annotations

import socket
import uuid
import ipaddress


def select_ip(explicit_ip: str | None = None) -> str:
    if explicit_ip:
        address = ipaddress.IPv4Address(explicit_ip)
        if address.is_multicast or address.is_unspecified or address.is_link_local:
            raise ValueError('Use an IPv4 address assigned to the LAN interface')
        return str(address)
    # UDP connect does not send a packet; it selects an outbound interface.
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        try:
            sock.connect(('192.0.2.1', 9))
            ip = sock.getsockname()[0]
            if ip and not ip.startswith('127.'):
                return ip
        except OSError:
            pass
    try:
        for ip in socket.gethostbyname_ex(socket.gethostname())[2]:
            if not ip.startswith('127.'):
                return ip
    except OSError:
        pass
    return '127.0.0.1'


class Advertiser:
    def __init__(self, name: str, ip: str, port: int, device_id: str):
        self.name, self.ip, self.port, self.device_id = name, ip, port, device_id
        self.zc = None
        self.info = None

    def start(self):
        # This import is deliberately deferred so --no-mdns also works offline.
        from zeroconf import IPVersion, ServiceInfo, Zeroconf
        safe_host = 'cast-debug-' + self.device_id[:8]
        self.info = ServiceInfo(
            '_googlecast._tcp.local.',
            f'{self.name}._googlecast._tcp.local.',
            addresses=[socket.inet_aton(self.ip)],
            port=self.port,
            properties={
                'id': self.device_id,
                'fn': self.name,
                'md': 'Cast Debug Receiver',
                've': '02',
                'ca': '5',
                'st': '0',
                'bs': self.device_id[:12],
                'nf': '1',
                'rs': 'Protocol inspection only - no media playback',
            },
            server=f'{safe_host}.local.',
        )
        self.zc = Zeroconf(interfaces=[self.ip], ip_version=IPVersion.V4Only)
        try:
            self.zc.register_service(self.info, allow_name_change=True)
        except Exception:
            self.zc.close()
            self.zc = None
            raise

    def stop(self):
        if self.zc:
            try:
                if self.info:
                    self.zc.unregister_service(self.info)
            finally:
                self.zc.close()
                self.zc = None


def make_device_id() -> str:
    return uuid.uuid4().hex

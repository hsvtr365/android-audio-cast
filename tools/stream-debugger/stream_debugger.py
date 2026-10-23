"""AudioCast HTTP/HLS diagnostic client. Python standard library only; no playback."""
import math
import queue
import threading
import time
import tkinter as tk
from dataclasses import dataclass
from tkinter import filedialog, messagebox, ttk
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin, urlsplit
from urllib.request import ProxyHandler, Request, build_opener


@dataclass
class Playlist:
    segments: list
    target: float
    ended: bool


def parse_playlist(text):
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if not lines or lines[0] != "#EXTM3U":
        raise ValueError("HLS 재생목록이 아니에요.")
    sequence, duration, target = 0, None, 0.0
    segments = []
    for line in lines[1:]:
        if line.startswith("#EXT-X-MEDIA-SEQUENCE:"):
            sequence = int(line.split(":", 1)[1])
        elif line.startswith("#EXT-X-TARGETDURATION:"):
            target = float(line.split(":", 1)[1])
        elif line.startswith("#EXTINF:"):
            duration = float(line.split(":", 1)[1].split(",")[0])
            if not math.isfinite(duration) or duration <= 0:
                raise ValueError("오디오 조각 길이가 올바르지 않아요.")
        elif not line.startswith("#"):
            if duration is None:
                raise ValueError("오디오 조각의 길이 정보가 없어요.")
            segments.append((sequence, duration, line))
            sequence += 1
            duration = None
    if not segments or duration is not None or not math.isfinite(target) or target <= 0:
        raise ValueError("재생목록의 필수 정보가 부족해요.")
    if any(round(item[1]) > target for item in segments):
        raise ValueError("오디오 조각 길이가 재생목록의 최대 길이를 넘어요.")
    return Playlist(segments, target, "#EXT-X-ENDLIST" in lines)


def inspect_aac(data):
    """Check packed AAC framing, including the optional leading ID3 tag."""
    offset = 0
    if data.startswith(b"ID3"):
        if len(data) < 10 or any(value & 128 for value in data[6:10]):
            raise ValueError("ID3 헤더가 잘못됐어요.")
        tag_size = sum(value << shift for value, shift in zip(data[6:10], (21, 14, 7, 0)))
        offset = 10 + tag_size + (10 if data[5] & 16 else 0)
    frames = 0
    while offset < len(data):
        header = data[offset:offset + 7]
        if len(header) < 7 or header[0] != 255 or header[1] & 246 != 240:
            raise ValueError("AAC 프레임 헤더가 잘렸거나 잘못됐어요.")
        frequency = (header[2] >> 2) & 15
        channels = ((header[2] & 1) << 2) | (header[3] >> 6)
        if frequency != 3 or channels != 2 or header[2] >> 6 != 1:
            raise ValueError("예상 형식인 AAC-LC · 48 kHz · 스테레오와 달라요.")
        size = ((header[3] & 3) << 11) | (header[4] << 3) | (header[5] >> 5)
        header_size = 7 if header[1] & 1 else 9
        if size <= header_size or offset + size > len(data) or header[6] & 3:
            raise ValueError("AAC 프레임 길이가 잘못됐거나 데이터가 잘렸어요.")
        offset += size
        frames += 1
    if not frames:
        raise ValueError("AAC 오디오 프레임이 없어요.")
    return frames, frames * 1024 / 48000


class Monitor:
    def __init__(self, url, stop, emit):
        self.url, self.stop, self.emit = url, stop, emit
        self.opener = build_opener(ProxyHandler({}))
        self.origin = urlsplit(url)[:2]

    def fetch(self, url):
        # A diagnostic session only reads the server explicitly entered by the user.
        if urlsplit(url)[:2] != self.origin:
            raise ValueError("재생목록에 다른 서버의 주소가 있어요.")
        request = Request(url, headers={"User-Agent": "AudioCast-Debugger/1", "Cache-Control": "no-cache"})
        with self.opener.open(request, timeout=3) as response:
            if urlsplit(response.url)[:2] != self.origin:
                raise ValueError("다른 서버로 이동하는 응답이에요.")
            data = response.read(2 * 1024 * 1024 + 1)
            if len(data) > 2 * 1024 * 1024:
                raise ValueError("응답이 예상 크기인 2 MB를 넘어요.")
            return data

    def run(self):
        checked, last_latest, changed = set(), None, time.monotonic()
        total = errors = 0
        while not self.stop.is_set():
            try:
                started = time.monotonic()
                playlist = parse_playlist(self.fetch(self.url).decode("utf-8-sig"))
                latency = (time.monotonic() - started) * 1000
                first, latest = playlist.segments[0][0], playlist.segments[-1][0]
                if last_latest is not None and latest < last_latest:
                    checked.clear()
                    self.emit("log", "목록 번호가 되돌아갔어요. 새 스트림으로 검사해요.")
                if last_latest is not None and first > last_latest + 1:
                    errors += 1
                    self.emit("log", f"검사하지 못한 구간: #{last_latest + 1}~#{first - 1}. 연결 지연이나 목록 이동을 확인해요.")
                if latest != last_latest:
                    changed = time.monotonic()
                last_latest = latest
                checked.intersection_update(item[0] for item in playlist.segments)
                stale = time.monotonic() - changed > max(12, playlist.target * 3)
                self.emit("state", "중계 종료 목록이에요" if playlist.ended else "주의: 목록 갱신이 멈췄어요" if stale else "연결됨 · 오디오 조각 검사 중")
                self.emit("playlist", f"#{first}~#{latest} · {len(playlist.segments)}개 · 목록 길이 {sum(s[1] for s in playlist.segments):.1f}초 · 응답 {latency:.0f}ms")
                for sequence, duration, uri in playlist.segments:
                    if self.stop.is_set():
                        break
                    if sequence in checked:
                        continue
                    try:
                        data = self.fetch(urljoin(self.url, uri))
                        frames, actual = inspect_aac(data)
                        if abs(actual - duration) > .03:
                            raise ValueError(f"목록 {duration:.3f}초와 AAC {actual:.3f}초가 달라요.")
                        checked.add(sequence)
                        total += 1
                        self.emit("log", f"정상 #{sequence} · {len(data):,} bytes · {frames}프레임 · {actual:.3f}초")
                    except (OSError, ValueError) as error:
                        errors += 1
                        self.emit("log", f"오류 #{sequence} · {safe_error(error)}")
                self.emit("counts", f"수신 성공 {total}개 · 오류/누락 감지 {errors}회")
                if playlist.ended:
                    break
            except HTTPError as error:
                if error.code == 503:
                    self.emit("state", "준비 중 · 휴대폰에서 오디오를 쌓고 있어요 (HTTP 503)")
                else:
                    errors += 1
                    self.emit("state", safe_error(error))
                    self.emit("log", safe_error(error))
            except (OSError, ValueError, UnicodeError) as error:
                errors += 1
                self.emit("state", "연결/형식 오류 · 주소와 네트워크를 확인해요")
                self.emit("log", safe_error(error))
            self.emit("counts", f"수신 성공 {total}개 · 오류/누락 감지 {errors}회")
            if self.stop.wait(2):
                break
        self.emit("done", "검사 종료")


def safe_error(error):
    # Stream URLs contain an access token, so do not put raw exception URLs in logs.
    if isinstance(error, HTTPError):
        return f"HTTP {error.code}" + (" · 중계 주소가 바뀌었거나 조각이 만료됐을 수 있어요" if error.code == 404 else "")
    if isinstance(error, URLError):
        return "서버 연결 실패 · 같은 Wi-Fi, 방화벽, 중계 실행 상태를 확인해요"
    if isinstance(error, ValueError):
        return str(error)
    return "수신 실패 · 연결 시간 초과 또는 연결 종료"


class App:
    def __init__(self, root):
        self.root, self.events = root, queue.Queue()
        self.stop = threading.Event()
        self.worker = None
        root.title("AudioCast 중계 진단")
        root.geometry("790x530")
        root.minsize(620, 400)
        body = ttk.Frame(root, padding=18)
        body.pack(fill="both", expand=True)
        ttk.Label(body, text="AudioCast 중계 진단", font=("맑은 고딕", 16, "bold")).pack(anchor="w")
        ttk.Label(body, text="설정 → 고급 설정 → 스트림 주소 복사 후 붙여 넣으세요.").pack(anchor="w", pady=(8, 4))
        self.url = tk.StringVar()
        ttk.Entry(body, textvariable=self.url).pack(fill="x", pady=(0, 10))
        buttons = ttk.Frame(body)
        buttons.pack(fill="x")
        self.start_button = ttk.Button(buttons, text="검사 시작", command=self.start)
        self.start_button.pack(side="left")
        self.stop_button = ttk.Button(buttons, text="중지", command=self.cancel, state="disabled")
        self.stop_button.pack(side="left", padx=8)
        ttk.Button(buttons, text="기록 저장", command=self.save).pack(side="right")
        self.labels = {}
        for key, value in (("state", "대기 중"), ("playlist", "재생목록: —"), ("counts", "수신 성공 0개 · 오류/누락 감지 0회")):
            self.labels[key] = tk.StringVar(value=value)
            ttk.Label(body, textvariable=self.labels[key], wraplength=720).pack(anchor="w", pady=4)
        ttk.Label(body, text="소리를 재생하지 않아요. AAC 프레임 구조·수신만 검사해요.\n실제 Cast 연결·볼륨·그룹 동기화·음질·수신기 지연은 검증하지 않아요.", foreground="#6d617d").pack(anchor="w", pady=(4, 8))
        self.log = tk.Text(body, height=12, wrap="word", state="disabled", font=("맑은 고딕", 10))
        self.log.pack(fill="both", expand=True)
        root.protocol("WM_DELETE_WINDOW", lambda: (self.stop.set(), root.destroy()))
        root.after(100, self.drain)

    def start(self):
        url = self.url.get().strip()
        parts = urlsplit(url)
        if parts.scheme not in ("http", "https") or not parts.hostname or not parts.path.endswith(".m3u8") or parts.username or parts.password:
            messagebox.showerror("주소 확인", "AudioCast에서 복사한 http://…/live.m3u8 주소를 넣어 주세요.")
            return
        self.stop = threading.Event()
        self.log.configure(state="normal")
        self.log.delete("1.0", "end")
        self.log.configure(state="disabled")
        self.start_button.configure(state="disabled")
        self.stop_button.configure(state="normal")
        self.labels["state"].set("연결 중…")
        self.labels["playlist"].set("재생목록: —")
        monitor = Monitor(url, self.stop, lambda kind, value: self.events.put((kind, value)))
        self.worker = threading.Thread(target=monitor.run, daemon=True)
        self.worker.start()

    def cancel(self):
        self.stop.set()
        self.labels["state"].set("검사를 마무리하고 있어요…")
        self.stop_button.configure(state="disabled")

    def drain(self):
        while True:
            try:
                kind, value = self.events.get_nowait()
            except queue.Empty:
                break
            if kind == "log":
                self.log.configure(state="normal")
                self.log.insert("end", f"{time.strftime('%H:%M:%S')} {value}\n")
                if int(self.log.index("end-1c").split(".")[0]) > 300:
                    self.log.delete("1.0", "2.0")
                self.log.see("end")
                self.log.configure(state="disabled")
            elif kind == "done":
                self.start_button.configure(state="normal")
                self.stop_button.configure(state="disabled")
                self.labels["state"].set(value)
            else:
                self.labels[kind].set(value)
        self.root.after(100, self.drain)

    def save(self):
        path = filedialog.asksaveasfilename(defaultextension=".txt", initialfile="AudioCast-진단.txt", filetypes=[("텍스트", "*.txt")])
        if path:
            try:
                with open(path, "w", encoding="utf-8") as file:
                    file.write("AudioCast 중계 진단 · HTTP/HLS/AAC 구조 검사 (Cast 기기 검증 아님)\n")
                    file.write("\n".join(value.get() for value in self.labels.values()) + "\n\n")
                    file.write(self.log.get("1.0", "end"))
            except OSError:
                messagebox.showerror("저장 실패", "기록을 저장할 수 없어요. 다른 위치를 선택해 주세요.")


if __name__ == "__main__":
    App(tk.Tk()).root.mainloop()

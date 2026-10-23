# Cast Protocol Inspector v0.1

Windows PC에서 실행하는 **비공식 Google Cast V2 수신기 디버거**. LAN에서 Cast 디바이스를 광고하고, TCP 8009/TLS 연결 및 Protobuf 프레임을 수신·로깅한다. 브라우저에서 웹 대시보드로 메시지, 연결 상태, 페이로드를 실시간 확인할 수 있다.

> **검증 범위:** 자체 테스트 송신기의 TLS → CONNECT → PING/PONG → GET_STATUS → LAUNCH → MEDIA LOAD 처리까지 통합 테스트. 실물 Chromecast/공식 Cast 앱 호환성은 검증하지 않았다. 이 프로그램은 Google 공인 Cast 기기가 아니며, Google 서명 DeviceAuth 자격 증명이 없다. Chrome/YouTube/Google Home이 인증 단계에서 거부할 수 있다. 음성·영상 재생, DRM, 화면 미러링을 구현하지 않는다. `LAUNCH`/`MEDIA_STATUS`는 모의 응답이다.

## Windows 실행 (Python 3.11 이상)

1. Python 설치: <https://www.python.org/downloads/windows/> (`python` 또는 Python Launcher `py` 사용 가능).
2. 압축을 풀고 `start_windows.bat` 실행. 첫 실행에 PyPI 패키지 설치를 위해 인터넷이 필요하다.
3. 브라우저에서 `http://127.0.0.1:8765` 열기. BAT가 브라우저를 열도록 설정돼 있다. 해당 포트가 사용 중이면 8766, 8767 등 다음 빈 포트를 사용하며 콘솔에 실제 주소를 표시한다.
4. 같은 Wi-Fi에 연결된 Android 또는 Cast Sender에서 `Cast Debug Receiver` 검색 시도. 기기 목록 미표시 또는 인증 거부는 환경/송신기 의존.
5. **확정적으로 기능 검증하려면** 서버 실행 중 `demo_windows.bat` 실행. 화면에 `PING`, `PONG`, `GET_STATUS`, `LAUNCH`, `LOAD`가 찍혀야 한다.

PowerShell 직접 실행:

```powershell
py -3 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m cast_inspector --name "Cast Debug Receiver" --ip 192.168.0.5
```

`--ip`는 **사용자 PC의 실제 LAN IPv4**로 수정. 지정하지 않으면 자동 감지한다. VPN/복수 NIC인 경우 `--ip` 명시를 권장한다.

### Windows 방화벽

Cast 접속을 위해 동일 LAN으로부터 **TCP 8009 인바운드**가 필요하다. 관리자 PowerShell에서 사설 네트워크 프로필에만 적용하는 예시:

```powershell
New-NetFirewallRule -DisplayName "Cast Debug Receiver 8009" -Direction Inbound -Protocol TCP -LocalPort 8009 -Action Allow -Profile Private
```

mDNS 검색은 UDP 5353 멀티캐스트를 사용하므로 Windows 네트워크 검색·공유기 멀티캐스트 격리 설정도 영향이 있다. 게스트 Wi-Fi, AP isolation, VPN 방화벽 때문에 발견되지 않을 수 있다. 디버거 대시보드는 `127.0.0.1:8765`에만 바인딩되어 외부 PC에서 직접 접속할 수 없다.

## 기능

- `_googlecast._tcp.local.` DNS-SD 광고 (`zeroconf`)
- TLS 1.2+ 수신, 자체 서명 인증서 자동 생성(개발 전용)
- CastMessage Protobuf 디코딩(정확한 필드 번호 및 길이 프리픽스)
- `tp.connection`, `tp.heartbeat`, `tp.deviceauth` 로깅
- `receiver` GET_STATUS/LAUNCH/STOP/SET_VOLUME/GET_APP_AVAILABILITY 모의 응답
- `media` LOAD/PLAY/PAUSE/SEEK/STOP/GET_STATUS 분석 및 모의 MEDIA_STATUS
- WebSocket 실시간 로그, 검색, 송수신 필터, 연결 상태, JSONL 내보내기
- 디스크 파일 `logs/cast-events.jsonl` 생성 및 재실행 시 기기 ID 유지(`logs/device-id.txt`)

## 명령줄 옵션

```text
--name "Cast Debug Receiver"  mDNS 광고 이름
--ip 192.168.0.5          mDNS 광고에 사용할 PC LAN IPv4
--listen 0.0.0.0          Cast TCP 수신 바인드 주소
--cast-port 8009         Cast TLS 수신 포트
--ui-port 8765           로컬 웹 대시보드 포트
--log-dir logs           JSONL·디버그 TLS 인증서 저장 경로
--open-browser            브라우저 자동 열기
--no-mdns                네트워크 광고 생략 (오프라인 테스트)
--include-raw            민감정보 포함 가능성이 있는 원본 Protobuf 바이트 기록
```

`--include-raw` 사용 시 로그에 인증/민감 페이로드가 그대로 남을 수 있으므로 실기기 트래픽 수집 전 주의. 기본은 원본 바이너리 미보관, JSON의 비밀정보로 보이는 키 자동 마스킹. 완전한 비식별화는 보장하지 않는다.

## 테스트

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

Linux/macOS에서는 `python3 -m venv .venv`, `./.venv/bin/python -m pip install -r requirements.txt`, `./.venv/bin/python -m cast_inspector`로 실행 가능.

## 구현 구조

```text
cast_inspector/
  protocol.py      Cast V2 길이 프리픽스·Protobuf codec
  certificates.py  자체 서명 TLS 인증서 생성
  discovery.py     zeroconf mDNS 서비스 광고
  server.py        TLS listener / Namespace dispatch / JSONL / WebSocket API
  static/index.html 실시간 웹 UI
scripts/demo_sender.py TLS를 통해 테스트 Cast 명령 전송
```

아키텍처 참고: <https://github.com/yukarikaname/openchromecast> (Rust 수신기 구현), <https://github.com/cast-web/protocol> (Cast V2 프레이밍·네임스페이스 문서), <https://chromium.googlesource.com/openscreen/+/HEAD/cast/docs/protocol_flow.md> (Chromium Cast 프로토콜 문서). **본 구현은 이 저장소들을 복제하거나 포크한 것이 아니라 독립적으로 작성한 Python 진단용 수신기다.** 실제 미디어 재생·확장된 호환성은 Rust openchromecast 활용을 후속 단계로 권장.

## 현재 한계

- 자체 서명 TLS 인증서 + Google DeviceAuth 인증서 부재. Google 공인 Cast 앱 통신은 실패할 수 있음.
- 수신기/미디어 응답은 **디버깅용 모의 상태**. 오디오 재생이나 앱 다운로드 없음.
- Cast Streaming(화면 미러링)은 별도 스트리밍 프로토콜이어서 구현 범위 제외.
- 공유 연결 상태가 프로세스 단위 전역으로 단순화되어 있어 다중 동시 세션의 실제 Cast 의미론을 완전히 재현하지 않는다.
- 기본 모의 수신기는 미지원 namespace 메시지에 응답하지 않는다.
- Python 3.11+ 권장; 실제 Windows 설치/방화벽/mDNS/물리 Android 호환성은 이 개발 환경에서 테스트하지 못했다.


## 2026-10-20 보완 및 실기기 확인

- Windows 실행은 `py`가 없어도 `python`으로 가상 환경을 만들어요. 설치된 의존성이 있으면 매번 설치하지 않아요.
- mDNS는 선택한 LAN 인터페이스에서 IPv4로 광고해요. VPN/복수 NIC 환경에서는 `--ip`를 지정해 주세요.
- TCP 연결, TLS 협상 실패, TLS 성공, 인증 차단, 모의 LAUNCH/LOAD 단계를 분리해요. 연결이 끊겨도 최근 50개 연결의 마지막 단계를 대시보드에 남겨요.
- 기기 인증 요청은 Protobuf 구조로 검사한 뒤 인증 불가 오류를 응답해요. Google이 신뢰하는 인증서가 없는 상태에서 성공했다고 응답하지 않아요.
- 재생 URL의 계정정보·쿼리·AudioCast 토큰 경로를 기본 로그에서 가려요. `--include-raw`는 이 보호를 받지 않아요.
- 필수 Protobuf 페이로드 누락과 프레임 수신 정체를 오류로 기록해요.

실기기 관측: PC `192.168.0.5`의 광고 후 폰 `192.168.0.28`에서 TCP·TLS·DeviceAuth 요청을 수신했고 `AUTH_BLOCKED`를 기록했어요. 이는 네트워크 도달 및 TLS 성공의 증거이며, 공식 Cast 앱 연결 완료나 재생 성공은 아니에요. 폰 USB 연결이 끊겨 목록의 최종 표시 여부는 미확인이에요.

테스트 11개 통과. 자체 송신기의 모의 LAUNCH/LOAD, 인증 오류 응답, 연결 이력, 잘못된 프레임·주소 및 URL 가림을 검사했어요. 로컬 대시보드 시각 검수는 미실시예요.

### 다음 단계의 한계

일반 Android/Chrome Cast 송신기는 기기 인증서를 검증해요. 자체 서명 TLS 인증서를 만들어도 Google이 신뢰하는 DeviceAuth 인증서가 되지는 않아요. Rust openchromecast를 통합해도 이 신뢰 체인 문제는 그대로예요. 현재 버전은 도달 단계와 실패 원인을 분석하는 도구이며 실제 Cast 기기의 대체품은 아니에요.

참고: [Chromium Cast 인증 코드](https://chromium.googlesource.com/experimental/chromium/src/+/refs/tags/84.0.4147.48/components/cast_channel/cast_auth_util.cc), [openchromecast 구현 및 인증 한계](https://github.com/yukarikaname/openchromecast).

# AudioCast 🎵📡

Android 기기에서 재생 중인 오디오를 실시간으로 캡처하여 **AAC/HLS 스트림**으로 변환하고, **Google Cast 기기(Google Home, Nest 오디오, 스피커 그룹 등)** 및 로컬 네트워크의 미디어 플레이어로 전송하는 오픈소스 Android 애플리케이션입니다.

---

## ✨ 주요 기능 (Features)

- **🎧 시스템 오디오 캡처 (AudioPlaybackCapture API)**
  - Android 10+ 공식 미디어 캡처 API를 활용하여 미디어 재생음(`USAGE_MEDIA`)을 무손실 수준으로 캡처합니다.
  - 마이크를 거치지 않고 내부 디지털 오디오 신호를 직접 캡처하므로 외부 소음이 섞이지 않습니다.
- **⚡ 실시간 AAC-LC 인코딩 & HLS 스트리밍**
  - 기기 하드웨어 `MediaCodec`을 이용한 실시간 48kHz Stereo AAC-LC 인코딩
  - 128 / 192 / 256 / 320 kbps 비트레이트 설정 지원
  - RFC 8216 표준 규격 준수 (ID3 33-bit 타임스탬프 삽입 및 Live HLS 슬라이딩 윈도우 지원)
- **📡 내장 경량 HTTP 스트리밍 서버**
  - 휴대폰의 Wi-Fi 로컬 IPv4 주소에 경량 HTTP 서버를 바인딩하여 안전하게 스트림 서빙
  - 네트워크 변경(Wi-Fi 재연결 등) 시 서버 자동 재바인딩 및 복구
- **🔊 Google Cast 연동 & 스피커 그룹 지원**
  - Google Cast SDK(MediaRouter)를 통해 개별 Cast 스피커뿐만 아니라 **Google Home 스피커 그룹**으로 멀티룸 동기화 재생 요청 가능
- **📊 실시간 오디오 스펙트럼 비주얼라이저**
  - 실제 PCM 데이터를 2048-point FFT로 실시간 분석하여 60Hz ~ 16kHz의 24개 주파수 대역 시각화
  - 유기적 웨이브 파동 링 및 주파수 반응형 UI
  - 화면이 꺼지거나 백그라운드 전환 시 FFT 분석 루프를 자동 중단하여 배터리 효율 최적화
- **📱 재생 앱 자동 감지 (MediaSession)**
  - 현재 재생 중인 음악 앱(예: YouTube Music, Spotify, Suno 등)의 이름과 앱 아이콘을 자동으로 감지하여 메인 카드에 표시
- **⏱ 지연 시간 & 버퍼 제어**
  - 목표 지연 시간을 6초~30초 범위에서 사용자 환경에 맞게 유연하게 설정 가능
- **🔗 외부 플레이어 호환 (스트림 주소 복사)**
  - Cast 기기가 없더라도 로컬 Wi-Fi 내의 PC나 다른 기기에서 VLC, Safari 등 HLS 지원 플레이어로 직접 청취 가능
- **🎨 현대적인 Material Design 3 DayNight UI**
  - 시스템 설정, 라이트 모드, 다크 모드 완벽 지원
  - 한국어 및 영어 다국어 지원 (Android 앱별 언어 설정 지원)

---

## 📱 스크린샷 및 UI 구성

- **메인 카드**: 원형 중계 시작/중지 버튼, 실시간 오디오 스펙트럼, 재생 중인 앱 정보, 전송 상태 배지
- **연결 대상 카드**: Google Cast 스피커 및 그룹 선택 모달 호출
- **정보 대시보드**: 네트워크 상태(IP), 현재 버퍼링 상태, 목표 지연 시간, 오디오 비트레이트
- **설정 패널(Bottom Sheet)**:
  - AAC 비트레이트 품질 선택 (128k ~ 320k)
  - 목표 지연 시간 설정 (6s ~ 30s)
  - 테마 설정 (시스템 기본 / 라이트 / 다크)
  - 언어 설정 (한국어 / English)
  - 스트림 URL 복사 (PC 및 타 기기 테스트용)
  - 샘플 테스트 톤 생성 기능 (테스트용 사인파 음원)

---

## 🛠 시스템 요구 사항 (Requirements)

- **Android OS**: Android 10 (API Level 29) 이상 (권장: Android 14+ / Target SDK 37)
- **네트워크**: 송신 휴대폰과 수신 기기(Google Home 등)가 **동일한 Wi-Fi 공유기(로컬 서브넷)**에 연결되어 있어야 합니다.
- **Java / JDK**: JDK 21 이상
- **Android Gradle Plugin**: 9.1.1 이상

---

## 🚀 빌드 및 실행 방법 (Build & Run)

### 1. 저장소 클론
```bash
git clone https://github.com/hsvtr365/android-audio-cast.git
cd android-audio-cast
```

### 2. Android SDK 설정
프로젝트 루트 디렉토리에 `local.properties` 파일을 생성하고 본인 환경의 Android SDK 경로를 지정합니다.

**Windows 예시:**
```properties
sdk.dir=C\:/Users/<Your-Username>/AppData/Local/Android/Sdk
```

**macOS / Linux 예시:**
```properties
sdk.dir=/Users/<Your-Username>/Library/Android/sdk
```

### 3. 디버그 APK 빌드
```bash
# Windows
.\gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```
빌드된 APK 파일은 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다.

### 4. 단위 테스트 실행
```bash
# Windows
.\gradlew.bat testDebugUnitTest

# macOS / Linux
./gradlew testDebugUnitTest
```

---

## 📖 사용 방법 (Quick Start)

1. **설치 및 실행**: 빌드된 APK를 기기에 설치하고 앱을 실행합니다.
2. **Wi-Fi 확인**: 휴대폰과 Cast 수신 기기가 동일한 Wi-Fi 네트워크에 연결되어 있는지 확인합니다.
3. **중계 시작**:
   - 메인 화면 중앙의 재생 버튼을 누릅니다.
   - 최초 실행 시 **화면 캡처(미디어 프로젝션)** 및 **알림 / 로컬 네트워크 권한**을 승인합니다.
4. **음악 재생**:
   - Suno, YouTube Music, Spotify 등 원하는 음악 앱에서 음악을 재생합니다.
5. **스피커 선택 (Cast)**:
   - 중계 시작과 캡처 동의가 끝나면, 연결된 스피커가 없는 경우 기기 선택 창이 자동으로 열립니다. 이미 연결 중이거나 연결된 경우에는 다시 열지 않습니다.
   - 스피커 카드를 탭하여 원하는 Google Home 기기 또는 스피커 그룹을 선택합니다.
   - 약 15초가량 세그먼트가 버퍼링된 후 스피커에서 스트리밍 재생이 시작됩니다.
   - 홈 화면에 스피커 연결 필요 여부와 설정한 지연 목표를 안내합니다. 실제 지연은 스피커의 버퍼링에 따라 더 길어질 수 있습니다.
6. **볼륨 조절**:
   - 스피커로 오디오가 중계되는 동안 휴대폰 자체 스피커 볼륨을 0으로 낮추어도 캡처는 지속됩니다.
   - Cast 연결 창의 개별 스피커 슬라이더는 MediaRouter가 볼륨 변경을 요청합니다. 실제 볼륨이 반영되지 않으면 연결 창의 Google Home 버튼으로 각 기기 볼륨을 조절하세요. Google Home이 설치되어 있지 않으면 공식 도움말이 열립니다.
7. **중계 종료**: 앱 내의 정지 버튼 또는 알림 창의 '중지' 액션을 눌러 서비스를 종료합니다.

---

## 📂 프로젝트 구조 (Architecture)

```
app/src/main/
├── AndroidManifest.xml
├── java/app/syntheticresin/audiocast/
│   ├── MainActivity.kt          # UI 뷰 바인딩, 권한 요청, 설정 바텀시트
│   ├── LiveService.kt           # 포그라운드 캡처 서비스, 인코더 및 라이프사이클 관리
│   ├── LocalStreamServer.kt     # 경량 로컬 HTTP 서버 (HLS m3u8 및 aac 서빙)
│   ├── HlsStream.kt             # AAC 패킷 파싱, ID3 타임스탬프 삽입, HLS 슬라이딩 창 버퍼링
│   ├── CastController.kt        # Google Cast SDK 세션 및 라우팅 제어
│   ├── AudioSpectrum.kt         # 2048-point FFT 기반 실시간 주파수 분석 엔진
│   ├── AudioRingView.kt         # 유기적 웨이브 링 시각화 커스텀 뷰
│   ├── WaveformView.kt          # 실시간 오디오 스펙트럼 캔버스 뷰
│   ├── MediaAppInfo.kt          # 미디어 세션 감지 및 활성 재생 앱 조회
│   ├── RelayOptionsProvider.kt  # Cast SDK 옵션 프로바이더
│   └── Diagnostics.kt           # 내부 로깅 및 진단 모듈
└── res/                         # UI 리소스, 벡터 드로어블, 다국어 문자열
```

---

## 🔒 개인정보 및 보안 정책 (Privacy & Security)

- **오디오 데이터 로컬 처리**: 오디오 스트림은 기기 메모리 내에서만 실시간 인코딩/버퍼링되며, 외부 원격 서버로 일체 전송되거나 디스크에 영구 저장되지 않습니다.
- **로컬 네트워크 제한**: 스트림은 동일 Wi-Fi 서브넷 내의 인증된 로컬 IP로만 서비스되며 세션마다 무작위 경로 URL이 부여됩니다.
- **DRM 및 캡처 정책 준수**: Android 표준 API를 엄격히 준수하며 원본 앱이 캡처를 금지한 오디오(DRM 보호 콘텐츠 등)는 캡처하지 않습니다.

---

## 📄 라이선스 (License)

- 아이콘 리소스: [Google Material Design Icons](https://github.com/google/material-design-icons) (Apache-2.0 License)
- 본 프로젝트 소스 코드는 오픈소스로 제공됩니다.


## Cast 수신기와 배포 (44.0.9)

기본 Cast 앱 ID는 `E1D7D101`이며, 수신기는
[고정 HTTPS 페이지](https://hsvtr365.github.io/android-audio-cast/)에서 실행됩니다.
수신기만 웹에서 제공하고 실제 오디오는 같은 Wi-Fi의 휴대폰에서 스피커로 전달합니다.
이전 기본 수신기를 비교하려면 `-PcastReceiverAppId=CC1AD845`로 빌드합니다.
수신기 소스와 운영 방법은 [receiver/README.md](receiver/README.md)를 참고하세요.

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat bundleRelease assembleRelease
```

Release 서명은 Git에서 제외된 `signing.properties`의 `storeFile`, `storePassword`,
`keyAlias`, `keyPassword`로 설정합니다. 키와 비밀번호는 저장소에 추가하지 마세요.
빌드 결과는 `app/build/outputs/bundle/release/`와 `app/build/outputs/apk/release/`에 생성됩니다.

실기기에서 폰 호스팅44.0.8의 홈 그룹 세 대 첫 소리9초이내와 Wi-Fi8초 차단 후 자동복귀를
확인했습니다. 복구 후18분45초의113개 재생 상태 표본에서 추가재시도·오류는 없었습니다.
이는 단기 시험 결과이며 모든 환경의 시작시간·장기 안정성 보장은 아닙니다.
44.0.9는 서명된 배포 파일 빌드 및 Release lint를 통과했지만 Play 업로드·승인은 아직 완료하지 않았습니다.

# AudioCast 개발용 수신기

44.0.8부터 동일 HTML을 Android 앱 assets에 포함하고 중계 서버의
`/receiver/index.html`에서 제공한다. 수신기 콘솔의 개발 URL은
`http://PHONE_IP:8088/receiver/index.html`이다. 중계를 먼저 시작해야 서버가 열린다.
폰의 Wi-Fi IP가 바뀌면 개발 URL도 변경해야 한다.
PC 서버 중지 상태에서 거실 단독 신규 수신기 실행 및 재생/복구를 확인했다.
시작 청취는19초1회, 재시험4초1회로 변동이 있어 보편적9초 보장은 하지 않는다.
공개 배포나 영구 호스팅으로 간주하지 않는다. 미게시 앱은 Cast 콘솔에 등록한 스피커에서만 실행된다.
홈 그룹 세 기기 등록 후 PC 서버를 끈 상태에서9초이내 첫 소리 및
Wi-Fi8초 차단 후 세 대 자동복귀를 사용자 청취로 확인했다.

```powershell
python receiver/serve.py --bind YOUR_LAN_IP
.\gradlew.bat assembleDebug testDebugUnitTest -PcastReceiverAppId=E1D7D101
```

`serve.py`는 이전 PC 비교 시험용이다.
44.0.9부터 기본 수신기 ID는 E1D7D101이다. 이전 기본 수신기 비교 빌드는 `-PcastReceiverAppId=CC1AD845`로 지정한다.
수신기 공개 전에는 고정 HTTPS 호스팅으로 URL을 변경하고 각 기기/홈 그룹을 다시 검증해야 한다.

이 수신기는 MPL의 재개 조건을 2초·2세그먼트로 설정한다.
44.0.7은 이 수신기를 사용할 때 지연 목표6초의 초기 전송 준비를3초로 줄인다.
표시한 지연 목표와 최초 소리 시간은 별개이며 실제 청취 지연은 아직 별도 측정하지 않았다.
수신기 이벤트는 콘솔에만 기록하며 PC로 전송하지 않는다.


## 고정 HTTPS 호스팅 (44.0.9)

- URL: https://hsvtr365.github.io/android-audio-cast/
- 소스 브랜치: `codex/cast-receiver`, 루트 `index.html` 및 `.nojekyll`만 배포.
- Cast 앱 E1D7D101은 Published / Unlisted로 게시됨.
- 수신기 HTML만 HTTPS에서 내려받고, 음악은 폰의 토큰이 있는 LAN HTTP URL에서 받는다.
- 새 주소의 실제 스피커 실행은 아직 검증하지 않았다. 기존 청취 시험은 폰 호스팅44.0.8 기준이다.
- 정적 HTML 변경 시 해당 브랜치의 index.html을 이 저장소 receiver/web/index.html과 동일하게 갱신한다.

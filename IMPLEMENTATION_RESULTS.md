
## 0.37 Google Play 서포터 배지

외부 URL 후원 구현/URL 검증 코드를 제거하고 Play Billing9.1.0 연결/상품조회/가격표시/구매흐름/서명검증/acknowledge/보류/취소/복원 구현. support_coffee, support_five_coffees, support_coffee_cake 비소모성 일회성 상품3개. 구매 완료+RSA 서명 검증 후 설정에 각 상품명 배지 표시. 가격은 Google 반환값만 사용. 중계 기능 제한 없음. 전경 진입 구매 조회 성공 시 보유 목록 갱신, 조회 실패 시 캐시 유지. 토큰/서명 로그 저장 없음. 기기 내 검증의 서버 대비 한계를 PLAY_BILLING_SETUP.md에 기록. 개인정보처리방침 구매처리 항목 추가.

최종 APK/unsigned AAB 빌드·lint·테스트13개 통과, fixture 미지정 호스트 재생1개 건너뜀(test-results/build-v037-delivery.log). APK dist/audiocast-0.37-debug.apk. 실제 결제/복원/환불, UI 실기기 검사 미완료. ADB 기기 없음. 로컬 브라우저 사용이 Work 설정에서 비활성화되어 결제 프로필 추가 사용자 보고 후 Console 재확인·상품 등록 못 함. 앱 등록 및 계정 프로필 변경/실제 청구/출시는 하지 않음. unsigned AAB는 업로드 가능한 최종 서명본이 아님.

## 0.37 실기기 설치 검사 추가

2026-10-19 SM_F971N에 dist/audiocast-0.37-debug.apk 업데이트 설치 성공, MainActivity 실행 및 package versionCode37/versionName0.37 확인. 세 후원 상품명, 가격 미조회 표시, 구매 불가 안내, 구매 복원/다시 시도 버튼을 실기기 UI 계층에서 확인(test-results/billing-home.xml). 상품 미조회 시 구매 버튼 disabled 확인. 검사 중 사용자가 설정/처리방침으로 이동해 화면이 바뀌었으므로 복원 버튼 탭 결과나 배지 위치를 확정 검증한 것으로 기록하지 않는다. 신규 앱 크래시 흔적은 crash 버퍼에서 발견하지 못함(오래된 Android 설정 앱 크래시만 존재). 실제 Play 결제·복원·환불과 새 캡처/실제 Cast 재생은 이번 검사에서 수행하지 않음. 시스템 캡처 동의 없이 라이브 캡처 검증 불가. 실제 카드 결제 없음.

## 0.38 권한 안내 흐름

첫 실행(업데이트 사용자 포함)에 권한 안내를 한 번 표시한다. 필수 LAN/오디오 캡처 권한과 선택 알림, 별도 선택 알림 접근을 구분. 권한 설정하기는 누락된 기본 런타임 권한만 한 요청 배열로 전달(Android 창은 분리 가능). 나중에 선택 가능, Android 앱 설정 링크 및 설정 내 권한 안내 재진입 버튼 추가. 앱 자동 표시 미허용 문구를 선택 사항으로 변경. 중계 시작 중 알림 POST_NOTIFICATIONS 요청 삭제. 미허용 필수 LAN/RECORD_AUDIO는 기존 버튼/시작 흐름에서 재요청, 영구 거부는 앱 설정 경로 제공. 알림 접근은 별도 기존 설명 후 직접 설정, 자동 강제 이동 없음. MediaProjection 동의는 시작 시 매 세션 유지.

빌드/lint 통과, 0.38 실기기 설치 성공. 첫 실행 안내의 제목/본문/권한 설정/나중에/앱 설정 버튼 확인. 나중에 탭 뒤 다른 화면으로 전환되어 홈 복귀는 판정하지 못함. 해당 스냅샷에 시스템 권한창은 나타나지 않음(test-results/permissions038.xml, permissions-later038.xml). 실제 권한 승인/거부는 대신하지 않았으며 새 캡처 검증 미수행. 병합 Manifest에 Bluetooth/위치/NEARBY_WIFI_DEVICES 권한 없음. ACCESS_LOCAL_NETWORK는 Android17 근처기기 그룹에 속하며 시스템 문구는 앱이 변경하지 못함.


## 0.39 첫 실행 시스템 권한 요청

사용자 요청으로 최초 안내 모달 자동 표시 제거. initialPermissionsRequested가 없을 때 누락 LAN/RECORD_AUDIO/POST_NOTIFICATIONS를 바로 Android 런타임 권한 요청에 전달하며 이후 앱 재실행 시 자동 재요청하지 않는다. 기존 설정의 수동 권한 안내/앱 설정/재요청 경로와 캡처 세션별 시스템 동의 유지. 빌드/lint 통과, 실기기 설치 완료. 권한 자체는 대신 승인하지 않음. 스토어 출시 전 고지 적정성 검토는 기존 미완료 항목 유지.

## 0.40 재생 앱 표시 사전 모달 제거

사용자 요청으로 showMediaAccess의 자체 설명 모달 제거. 기존 버튼을 누르면 Android의 앱별 알림 접근 설정으로 직접 이동, 미지원이면 알림 접근 목록으로 fallback. 선택적 기능 및 Android 시스템 경고/직접 승인 유지. 빌드/lint 통과. 실기기 설치 성공. 설정 이동/권한 승인은 이번에 대신 수행하지 않음. 스토어 제출 전 선택적 접근 고지의 적정성 검토는 계속 필요.

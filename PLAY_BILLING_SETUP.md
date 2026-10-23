# AudioCast Google Play 결제 설정

0.37: 외부 후원 링크를 제거하고 Play Billing 9.1.0으로 전환. 결제 프로필 추가는 사용자 보고이며 현재 브라우저 접근이 비활성화되어 Console 상태는 재확인하지 못했다. 앱은 app.syntheticresin.audiocast / Console AudioCast를 사용한다.

## 등록할 일회성 제품

| 제품 ID (정확히 일치) | 영어 상품명 | 미국 기준 목표 가격 | 실제 제공 혜택 |
|---|---|---|---|
| support_coffee | Coffee Supporter badge | USD 2 | 설정에 Coffee Supporter 배지 |
| support_five_coffees | Five Coffees Supporter badge | USD 10 | 설정에 Five Coffees Supporter 배지 |
| support_coffee_cake | Coffee and Cake Supporter badge | USD 20 | 설정에 Coffee and Cake Supporter 배지 |

각 상품은 비소모성 일회성 구매다. 구독/자동 갱신/실물 커피 배송/자선 기부가 아니다. 같은 상품을 다시 구매할 수 없으며 세 상품은 개별 구매 가능하다. 앱 중계 기능은 모든 사용자에게 무료다.

권장 영어 설명: “A one-time purchase that unlocks the [Coffee / Five Coffees / Coffee and Cake] Supporter badge in AudioCast Settings. Audio relay features remain free. No subscription or physical goods.”

## Console에서 남은 작업

1. 수익 창출 → 제품 → 일회성 제품에서 위 ID 3개 등록. 상품별 비소모성 일반 구매 옵션 하나, 대여/기간제/할인 offer 없이 구성한다. 이름·설명에 실제 배지 혜택 명시.
2. 기본 USD 목표 가격을 설정하고 Google Play가 환산한 판매 국가별 가격/세금/판매 가능 지역을 검토한다. $2/$10/$20은 앱에서 고정 가격으로 표시하지 않는다.
3. 준비한 상품과 구매 옵션을 활성화한다. 현재 코드의 oneTimePurchaseOfferDetails와 단일 일반 구매 옵션 구성이 일치해야 한다.
4. 서명된 AAB를 내부 테스트에 업로드한다. 현재 dist의 unsigned AAB는 업로드 완료본이 아니다. 업로드 키와 Play App Signing 설정 필요.
5. 라이선스 테스터와 내부 테스트 참여 계정으로 Google Play를 통해 설치한다. 직접 설치한 debug APK는 실제 상품 조회/결제 성공 검증을 대신하지 못한다.
6. 테스트 결제로 완료/취소/보류 후 완료/앱 재실행/구매 복원/환불 후 재조회/오프라인을 확인한다. 테스트 화면에 테스트 결제수단 표시를 확인하고 실제 사용자 카드로 임의 청구하지 않는다.
7. 개인정보처리방침 공개 웹 버전과 데이터 보안 양식을 Billing 추가 후 다시 검토한다.

## 구현/제약

- Google Play 반환 현지 가격 표시. 상품/가격 조회 실패 시 해당 구매 버튼 비활성, 복원/재시도 제공.
- 구매 완료 상태와 앱 전용 RSA 공개 키로 서명 확인 후 배지 지급, acknowledge 실행. 결제 대기는 지급하지 않는다. 구매 확인 실패는 이후 복원/전경 진입 때 재시도한다.
- 앱 전경 복귀 시 구매 재조회. 성공한 전체 조회 결과로 로컬 보유 목록을 교체해 반환되지 않는 구매의 배지를 제거한다. 오프라인/조회 오류는 이전 상태 유지.
- 토큰/서명을 진단 로그에 기록하지 않는다. 공개 키는 공개 정보이며 비밀키가 아니다.
- 별도 서버 없는 기기 내 서명 검증이다. 수정된 클라이언트와 구매 토큰 재사용 공격 방어, 환불·취소의 실시간 검증은 서버 검증보다 제한된다. 제품 확대 시 Google Developer API 기반 서버 검증/RTDN을 검토한다. 실시간 환불 검증 완료라고 주장하지 않는다.
- 현재 실제 Google Play 구매/복원/환불 검증은 미완료이며 스토어 승인 보장은 없다.

공식 근거: https://developer.android.com/google/play/billing/integrate

# TimeMachine 소개 페이지 문안

한국어 · [English](project-page.md)

GitHub, Modrinth와 릴리스 페이지에서 사용할 문안입니다. 실제로 관리하는 홈페이지, 후원 계정, 지원 주소와 호환성만 추가하십시오.

## 메타데이터

- 제목: `TimeMachine`
- 제작자 표기: `TimeMachine by PLAYCITY BLOCK`
- 제작팀: `PLAYCITY BLOCK`
- 권장 GitHub 저장소명: `timemachine-minecraft-plugin`
- GitHub 설명: `Verifiable FULL and incremental region backups for Paper, with chain validation and offline restore export.`
- 권장 Modrinth slug: `timemachine`
- 대체 Modrinth slug: `playcity-block-timemachine`
- 플랫폼과 loader: Paper
- 지원 서버: Paper 26.2 build 111 이상 26.2 계열
- Java: 25
- 라이선스: MIT
- 환경: 서버 필수, 클라이언트 불필요
- 외부 필수 의존성: 없음
- 권장 배포 유형: beta
- 배포 파일: `Timemachine-0.4.0.jar`
- 제공 언어: 영어와 한국어
- 아이콘: `assets/timemachine-icon.png`
- 커버: `assets/timemachine-cover.png`

## 소개 문안

### 한 줄

Paper 지역 데이터를 검증 가능한 FULL·증분 스냅샷으로 보관하고 오프라인 복원본을 만드는 백업 플러그인.

### 설명

TimeMachine은 Paper의 `region`, `entities`, `poi` 데이터를 FULL·증분 스냅샷으로 보관합니다. 기본 SAFE 모드는 모든 추적 파일의 SHA-256을 비교하고, 복사한 파일을 다시 검증합니다.

`/tmb status`로 진행률을 확인하고 `/tmb doctor`로 저장소와 일정을 점검할 수 있습니다. 다른 저장소로 옮긴 스냅샷은 archive root와 reconcile로 다시 연결하며, 보존 정리는 완전한 복원 체인 단위로 처리합니다.

복원에는 같은 JAR의 오프라인 `restore verify`와 `restore export`를 사용합니다. Export는 새 디렉터리에만 기록하고 운영 월드를 자동으로 교체하지 않습니다.

TimeMachine은 서버 전체 백업 도구가 아닙니다. 플레이어 데이터, `level.dat`, datapack, 플러그인 데이터, 설정과 기타 파일은 별도의 검증된 원격 백업으로 보호해야 합니다.

## 핵심 기능

- FULL 기준점과 증분 스냅샷
- SAFE SHA-256 또는 선택 가능한 FAST metadata 변경 감지
- 원본 변경 감지와 제한된 복사 재시도
- 파일, 매핑, 삭제, checksum과 부모 체인 검증
- 진행률과 운영 진단
- 핀과 최소 두 체인을 보호하는 보존 정리
- 오프라인 `restore list`, `verify`, `export`
- 파일시스템 reconcile을 지원하는 SQLite 검색 인덱스
- 영어와 한국어 출력

## 빠른 예시

```text
/tmb backup
/tmb status
/tmb doctor
```

```yaml
schedule:
  enabled: true
  daily-times:
    - "04:00"
  timezone: "system"
```

## 이미지

- 아이콘: `assets/timemachine-icon.png`
- 갤러리 커버: `assets/timemachine-cover.png`
- 권장 제목: `Verified snapshot timeline`
- 권장 설명: `FULL and incremental region snapshots form a verifiable recovery chain.`

운영 화면은 테스트 또는 실제 서버에서 캡처한 것만 추가하십시오. `/tmb status`, `/tmb doctor`, 오프라인 export와 간단한 기본 설정이 적합합니다. 플러그인에 없는 GUI를 합성하지 마십시오.

## 함께 표시할 제한

- `region`, `entities`, `poi`의 `.mca` 파일만 백업
- Folia와 hot reload 미지원
- 온라인 또는 자동 in-place restore 미지원
- 압축, 암호화, 클라우드 업로드와 webhook 미지원
- 별도의 검증된 off-site 백업 필요

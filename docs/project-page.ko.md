# TimeMachine 소개 페이지 문안

한국어 · [English](project-page.md)

Modrinth와 GitHub에 사용할 소개 문안과 제출 점검표입니다. 영어판 [project-page.md](project-page.md)가 기준 문서이며, 실제 제출 필드에는 영어 문안을 사용합니다. 이 문서의 모든 내용은 플러그인의 descriptor, 설정, 소스에서 도출했습니다. 실제로 존재하고 프로젝트 소유자가 관리하지 않는 홈페이지, 후원 계정, 지원 주소, 성능 수치, 호환성 주장을 추가하지 마십시오.

---

## 한 줄 요약

Modrinth 요약 필드에는 영어 원문을 붙여 넣습니다.

```text
Verifiable FULL and incremental region backups for Paper, with chain verification and an offline restore export tool built into the same JAR.
```

한국어 대응 표현(참고용):

```text
Paper 지역 데이터를 검증 가능한 FULL·증분 스냅샷으로 보관하고, 같은 JAR에 내장된 오프라인 복원 export를 제공하는 백업 플러그인.
```

## 전체 설명 (한국어 참고본)

Modrinth 본문에는 영어판을 붙여 넣습니다. 아래는 한국어 커뮤니티 공지나 번역 검토에 사용하는 대응본입니다.

```markdown
## TimeMachine

**Paper를 위한 검증 가능한 FULL·증분 지역 데이터 백업.**

TimeMachine by PLAYCITY BLOCK

TimeMachine은 Paper 월드의 `.mca` 파일을 확인 가능한 스냅샷으로 복사합니다. 스냅샷은 평범한 디렉터리입니다. 복사한 지역 파일, 모든 항목의 manifest, SHA-256 체크섬 목록, 데이터를 되돌릴 때 필요한 월드 매핑이 그대로 들어 있습니다. 독자 형식 컨테이너로 묶지 않으므로 플러그인이 없어도 스냅샷 내용을 읽을 수 있습니다.

필터 없는 첫 백업은 FULL 기준점이 됩니다. 이후 백업은 변경된 파일만 저장하며 그 기준점에서 이어지는 체인을 만듭니다. `/tmb verify`는 체인을 FULL 기준점까지 거슬러 올라가며 크기, 해시, manifest, 부모 관계를 다시 확인합니다.

복원은 의도적으로 수동입니다. 같은 JAR을 오프라인 명령줄 도구로 실행해 체인을 검증하고 **새 디렉터리**로 내보냅니다. 이미 존재하거나 백업 저장소와 겹치는 경로는 거부하며, 실행 중인 월드를 대신 교체하지 않습니다.

### 백업 대상

로드된 월드마다 `region`(청크와 블록 엔티티), `entities`, `poi`의 `.mca` 파일을 복사합니다. 월드는 UUID로 추적하므로 이름을 바꿔도 체인이 끊어지지 않습니다.

**TimeMachine은 서버 전체 백업 도구가 아닙니다.** 플레이어 데이터, `level.dat`, datapack, 플러그인 데이터, 서버 설정은 범위 밖입니다. 그 파일들은 별도로 검증된 원격 백업을 유지하십시오.

### 기능

- FULL 기준점과 증분 스냅샷, 증분 연속성을 신뢰할 수 없을 때 자동 FULL 승격
- SAFE 변경 감지(모든 추적 파일의 SHA-256) 또는 선택 가능한 FAST 감지(크기와 수정 시각)
- 복사하는 모든 파일을 해시·검증하고, 복사 중 원본이 바뀌면 재시도
- `/tmb verify`가 경로, 월드 매핑, 크기, 해시, 체크섬 manifest, 삭제 항목, 기록된 개수까지 체인 전체를 확인
- 핀, 최소 두 체인 보호, 미리보기 후 토큰 확인을 갖춘 체인 단위 보존 정리
- 같은 JAR에서 실행하는 오프라인 `restore list`, `restore verify`, `restore export`
- archive 저장소로 옮긴 스냅샷을 다시 연결하는 로컬 SQLite 검색 인덱스와 reconcile
- 실시간 진행률, `/tmb doctor` 진단, 몰아치지 않는 catch-up을 갖춘 일간·월간 일정
- 영어와 한국어 출력, 기본값은 플레이어의 클라이언트 언어를 따름

### 빠른 시작

```text
/tmb backup      # 필터 없는 첫 백업이 FULL 기준점이 됩니다
/tmb status
/tmb doctor
```

`plugins/Timemachine/config.yml`에서 매일 백업을 켠 뒤 `/tmb reload`를 실행합니다.

```yaml
schedule:
  enabled: true
  daily-times:
    - "04:00"
  timezone: "system"
```

### 오프라인 복원

서버를 정지한 뒤 같은 JAR을 실행합니다.

```text
java -jar Timemachine-<version>.jar restore list
java -jar Timemachine-<version>.jar restore verify --snapshot <snapshotId>
java -jar Timemachine-<version>.jar restore export --snapshot <snapshotId> --output <새-디렉터리>
```

export는 전체 체인을 검증하고 새 디렉터리에만 기록하며, 복원한 모든 파일의 크기와 SHA-256을 다시 확인합니다. 이미 존재하거나 백업 저장소와 겹치거나 심볼릭 링크를 포함한 경로는 거부합니다.

### 요구 사항

- `26.2` API 계열 Paper (`api-version: '26.2'`, `paper-api 26.2.build.111-stable`로 빌드)
- Java 25 이상
- 클라이언트 설치, 외부 의존성, 데이터베이스 서버 모두 불필요

### 제한

- 로드된 월드의 `region`, `entities`, `poi` 아래 `.mca` 파일만 백업
- 온라인 또는 자동 in-place 복원 없음
- 압축, 암호화, 중복 제거, 클라우드 업로드, webhook 없음
- Folia 지원과 hot reload 없음
- 별도의 검증된 원격 백업을 대체하지 않음

### 권한

`timemachine.viewer`(status, doctor, history) · `timemachine.operator`(viewer 범위와 backup, verify) · `timemachine.admin`(전체, 기본 op). 개별 `timemachine.*` 노드도 그대로 사용할 수 있습니다.

### 네트워크 동작

TimeMachine은 외부 네트워크 통신을 하지 않습니다. 소켓, 원격 서비스, 텔레메트리, 실행 중 다운로드, 원격 데이터베이스가 모두 없습니다. SQLite는 JAR에 포함된 드라이버로 접근하는 로컬 파일입니다.

MIT 라이선스로 배포합니다.
```

## GitHub 저장소 메타데이터

- 저장소 설명: `Verifiable FULL and incremental region backups for Paper, with chain verification and offline restore export.`
- 권장 저장소 slug: `timemachine-minecraft-plugin`
- 토픽: `minecraft`, `paper`, `paper-plugin`, `backup`, `incremental-backup`, `sqlite`, `java`

---

## Modrinth 제출 점검표

필드 값은 영어판 점검표와 동일합니다. 게시 전에 모든 항목을 확인하십시오.

### 프로젝트 식별

| 필드 | 값 |
|---|---|
| 프로젝트 유형 | Plugin |
| 제목 | `TimeMachine` |
| Slug | `timemachine` (사용 중일 때만 `playcity-block-timemachine`) |
| 게시자/팀 | `PLAYCITY BLOCK` |
| 문안에 사용하는 제작자 표기 | `TimeMachine by PLAYCITY BLOCK` |
| 요약 | 위의 영어 한 줄 요약 |
| 카테고리 | Utility, Management |

### 환경

| 필드 | 값 | 근거 |
|---|---|---|
| 클라이언트 | 미지원 | 클라이언트 코드·리소스·프로토콜 사용 없음 |
| 서버 | 필수 | `plugin.yml` main 클래스, 모든 기능이 서버 측 |
| Loader | Paper | `plugin.yml`의 `api-version: '26.2'` |
| 게임 버전 | Paper `26.2` API 계열이 지원하는 Minecraft 버전 | 빌드가 대상으로 삼지 않는 버전은 표기하지 않음 |
| 설명 본문의 Java 표기 | Java 25 이상 | Gradle `options.release = 25` |

### 의존성

| 필드 | 값 |
|---|---|
| 필수 의존성 | 없음 |
| 선택 의존성 | 없음 |
| 포함 라이브러리 | Xerial SQLite JDBC(Apache-2.0 / BSD-2-Clause), SQLite(퍼블릭 도메인). JAR에 shade |
| Paper API | compile-only, JAR에 포함하지 않음 |

### 라이선스

| 필드 | 값 |
|---|---|
| 라이선스 | MIT |
| 라이선스 파일 | `LICENSE`, 저작권자 표기 변경 없음 |
| 서드파티 고지 | 재배포 시 `THIRD_PARTY_NOTICES.md`를 함께 제공하거나 링크 |

### 버전 업로드

| 필드 | 값 |
|---|---|
| 버전 번호 | `plugin.yml`과 JAR 파일명과 일치해야 함 |
| 파일 | `Timemachine-<version>.jar` |
| 배포 채널 | `1.0.0` 이전에는 Beta |
| 변경 기록 | `CHANGELOG.md`의 해당 절을 그대로 복사. 없는 이력을 만들지 않음 |

### 콘텐츠 공개 (Content Rules, 2026-08-13)

| 공개 항목 | 답변 | 사유 |
|---|---|---|
| Contains AI-generated content | **Yes** | 소개 페이지 설명 문안이 생성된 텍스트입니다. |
| Advertising | No | 플러그인에 광고나 홍보 노출이 없습니다. |
| Paid features | No | 유료 기능, 결제 장벽, 후원 유도가 없습니다. |
| Telemetry | No | 사용 데이터나 분석 정보를 전송하지 않습니다. |
| External system interactions | **Yes** | 설정된 서버 파일 경로, 로컬 archive root, 로컬 SQLite 인덱스와 오프라인 export 목적지를 읽고 씁니다. 네트워크 통신은 하지 않습니다. |
| Derivative content | No | 다른 프로젝트의 포크나 상당 부분을 재배포한 프로젝트가 아닙니다. |
| Photosensitivity warning | No | 깜박이는 시각 콘텐츠나 클라이언트 UI가 없습니다. |
| Archived project | No | 보관된 프로젝트가 아니라 현재 공개를 준비하는 프로젝트입니다. |

**이미지.** AI로 생성했거나 AI에서 파생한 이미지는 Modrinth 소개 페이지 어디에도 사용할 수 없습니다. 아이콘, 갤러리, 설명 본문 모두 해당합니다. 저장소의 현재 `assets/timemachine-icon.png`와 `assets/timemachine-cover.png`는 Modrinth에 사용할 수 없으며 업로드하지 않습니다. 스크린샷은 실제 서버에서 촬영해야 하며, 플러그인에 없는 GUI를 합성하지 마십시오.

### 미디어

| 항목 | 값 |
|---|---|
| 아이콘 | 사람이 직접 만든 비-AI 이미지가 준비될 때까지 비워 둡니다. |
| 갤러리 커버 | 사용 가능한 비-AI 이미지나 실제 서버 스크린샷이 준비될 때까지 비워 둡니다. |
| 권장 스크린샷 | 실제 서버 콘솔의 `/tmb status`와 `/tmb doctor`, 실제 오프라인 `restore export` 실행, 실제 기본 설정 파일. |

### 링크

실제로 존재하고 소유자가 관리하는 링크만 설정합니다. 그 외에는 임시 주소를 넣지 말고 비워 두십시오.

| 필드 | 값 |
|---|---|
| Source | 공개 후 이 저장소 |
| Issues | 이 저장소의 이슈 트래커 |
| Wiki / Discord / 후원 | 실제 소유한 대상이 생기기 전까지 비워 둠 |

### 게시 전 최종 확인

- [ ] 제목, 요약, 설명, 요구 사항, 제한이 `README.md`와 일치합니다.
- [ ] 설명에 서버 전체 백업이 아니라 지역 데이터 백업이라는 점이 분명히 적혀 있습니다.
- [ ] 복구 안내가 서버를 완전히 정지한 상태의 오프라인 export 절차로 시작합니다.
- [ ] 보존 정리가 기본 비활성이고 체인 단위라는 설명이 있습니다.
- [ ] 없는 URL, 지원 채널, 계정, 성능 수치, 릴리스 이력이 어디에도 없습니다.
- [ ] 업로드하는 JAR이 릴리스 검증을 통과한 파일과 바이트 단위로 동일합니다.
- [ ] 위의 콘텐츠 공개 항목을 지정된 대로 답변했습니다.

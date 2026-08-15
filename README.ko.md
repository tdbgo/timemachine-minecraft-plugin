# TimeMachine

**Paper를 위한 검증 가능한 FULL·증분 지역 데이터 백업.**

TimeMachine by PLAYCITY BLOCK

한국어 · [English](README.md)

![TimeMachine 백업 타임라인](assets/timemachine-cover.png)

TimeMachine은 Paper 월드의 `.mca` 파일을 확인 가능한 스냅샷으로 복사합니다. 스냅샷은 평범한 디렉터리입니다. 복사한 지역 파일, 모든 항목의 manifest, SHA-256 체크섬 목록, 데이터를 되돌릴 때 필요한 월드 매핑이 그대로 들어 있습니다. 독자 형식 컨테이너로 묶지 않으므로 플러그인이 없어도 스냅샷 내용을 읽을 수 있습니다.

필터 없는 첫 백업은 FULL 기준점이 됩니다. 이후 백업은 변경된 파일만 저장하며 그 기준점에서 이어지는 체인을 만듭니다. `/tmb verify`는 체인을 FULL 기준점까지 거슬러 올라가며 크기, 해시, manifest, 부모 관계를 다시 확인합니다. "백업이 있다"와 "백업이 복원된다"를 서로 다른 질문으로 두고, 필요해지기 전에 답을 확인할 수 있습니다.

복원은 의도적으로 수동입니다. 같은 JAR을 오프라인 명령줄 도구로 실행해 체인을 검증하고 **새 디렉터리**로 내보냅니다. 이미 존재하거나 백업 저장소와 겹치는 경로는 거부합니다. TimeMachine은 실행 중인 월드를 대신 교체하지 않습니다.

TimeMachine은 서버 전체 백업 도구가 아닙니다. region·entity·POI 데이터를 보호하며 플레이어 데이터, `level.dat`, datapack, 플러그인 데이터, 서버 설정은 보호하지 않습니다. 그 파일들은 별도로 검증된 원격 백업을 유지하십시오.

---

## 1. 요구 사항

| 항목 | 요구 사항 |
|---|---|
| 서버 | `26.2` API 계열 Paper. `plugin.yml`의 `api-version`은 `'26.2'`이며 `paper-api 26.2.build.111-stable`로 빌드합니다. |
| Java | 25 이상 (`--release 25`로 컴파일). |
| 클라이언트 | 필요 없음. 플레이어는 아무것도 설치하지 않습니다. |
| 외부 의존성 | 없음. SQLite JDBC 드라이버는 JAR에 포함되어 있습니다. |
| 디스크 | 스냅샷 저장 공간과 `backup.minimum-free-space-mib`(기본 1024 MiB) 예비 공간. |

Folia는 지원하지 않습니다. PlugMan 같은 hot-reload 도구와 Paper의 reload 명령도 지원하지 않으므로 서버를 재시작하십시오.

## 2. 설치

1. `Timemachine-<version>.jar`를 서버의 `plugins` 폴더에 넣습니다.
2. 서버를 정상 시작합니다. TimeMachine이 안전한 기본값으로 `plugins/Timemachine/config.yml`을 만들고 저장소를 백그라운드에서 초기화합니다.
3. 콘솔에 TimeMachine이 활성화되었다는 메시지가 나올 때까지 기다립니다. 그 전에는 명령이 `TimeMachine storage is still initializing`으로 응답합니다.

설치 마법사, 데이터베이스 서버, 계정은 필요하지 않습니다.

## 3. 5분 빠른 시작

```text
/tmb backup                 # 필터 없는 첫 백업이 FULL 기준점이 됩니다
/tmb status                 # 진행률, 마지막 결과, 다음 예약 실행
/tmb doctor                 # 저장소, 여유 공간, 체인, 일정, 범위 경고
/tmb history                # 최근 스냅샷 (기본 5개)
/tmb verify <snapshotId>    # 스냅샷과 전체 체인을 다시 확인
```

스냅샷 ID는 `2026/2026-08-16_04-00-00-123_manual_1a2b3c4d` 형태입니다. 연도 디렉터리, 로컬 시각, 트리거, 임의 접미사로 구성됩니다. `/tmb history`에서 복사해 사용하십시오.

매일 자동 백업을 켜려면 `plugins/Timemachine/config.yml`을 편집합니다.

```yaml
schedule:
  enabled: true
  daily-times:
    - "04:00"
  timezone: "system"
```

그다음 `/tmb reload`를 실행하고 `/tmb status`로 다음 실행 시각을 확인합니다.

마무리는 테스트 장비에서 하십시오. 서버 사본을 정지하고 오프라인 export를 실행한 뒤 내보낸 월드를 열어 봅니다. 한 번도 복원해 보지 않은 백업은 가정일 뿐입니다.

## 4. 기본 사용법

### 백업 대상

월드 필터를 통과한 **로드된** 월드마다 다음 세 디렉터리를 검사하고 `.mca` 파일만 복사합니다.

- `region` — 청크와 블록 엔티티
- `entities` — 엔티티 데이터
- `poi` — 관심 지점

월드는 UUID로 추적하므로 이름을 바꿔도 체인이 끊어지지 않습니다. 월드 이름과 NamespacedKey는 표시와 복원 매핑을 위해 기록합니다. 로드되지 않은 월드는 백업하지 않습니다.

### FULL, 증분, scoped-full 스냅샷

| 종류 | 생성 시점 | 내용 |
|---|---|---|
| `FULL` | 첫 백업, 월드 필터 없는 `/tmb backup --full`, 자동 승격 | 추적 대상 전체. 부모 없음. 새 체인의 기준점이 됩니다. |
| `INCREMENTAL` | 일반 백업 | 이전 스냅샷 이후 변경된 파일과 삭제된 경로 목록. 부모와 체인의 FULL 기준점을 가리킵니다. |
| `SCOPED_FULL` | `/tmb backup <world> --full` | 선택한 월드의 전체 데이터. 현재 체인의 자식으로 보관하며 새 체인을 **시작하지 않습니다**. |

전역 기준점이 되는 것은 필터 없는 FULL뿐입니다. 증분 연속성을 신뢰할 수 없을 때 — 인덱스가 없거나 읽을 수 없을 때, 인덱스를 `.bak` 사본에서 복구했을 때, 활성 체인이 끊어졌을 때, 월드의 실제 저장 경로가 바뀌었을 때 — TimeMachine이 다음 필터 없는 백업을 스스로 FULL로 승격합니다. 그 상태에서 월드 필터가 있는 요청은 불완전한 기준점을 조용히 만드는 대신 이유를 설명하며 거부합니다.

증분 백업에서 변경·삭제 파일이 하나도 없고 `backup.skip-if-no-change`가 켜져 있으면(기본값) 스냅샷을 만들지 않습니다.

### SAFE와 FAST 변경 감지

| 모드 | 변경 없는 파일 스캔 | 크기와 수정 시각이 유지된 내용 변경 감지 |
|---|---|---|
| `safe` (기본) | 모든 추적 파일을 읽어 SHA-256을 계산하고 비교 | 가능 |
| `fast` | 크기와 수정 시각만 비교 | 불가능 |

안전 경계는 이 한 줄이 전부입니다. 실제로 복사하는 파일은 두 모드 모두 SHA-256으로 해시하고 검증합니다. 차이는 변경이 없어 보이는 파일을 읽지 않고 신뢰하는지 여부입니다. `fast`가 켜져 있는 동안 `/tmb doctor`는 계속 경고를 표시합니다. 측정한 스캔 단계 차이와 측정 조건은 [SAFE/FAST 벤치마크](docs/change-detection-benchmark.ko.md)를 참고하십시오.

모든 복사는 안정성 검사를 거칩니다. 복사 전후로 원본 속성을 읽고, 복사 도중 원본이 바뀌면 최대 세 번까지 재시도하며, 스냅샷으로 옮기기 전에 해시를 계산합니다.

### 검증

`/tmb verify <snapshotId>`는 부모 체인을 FULL 기준점까지 따라가며 형식 버전, 순환과 누락된 부모, 월드 매핑, 항목 경로와 식별자, 파일 크기, SHA-256, 체크섬 manifest, 삭제 항목, 기록된 개수를 확인합니다. 스냅샷 내용의 심볼릭 링크는 거부합니다. 검증은 주 저장소와 설정된 모든 archive root를 검색합니다.

체인이 유효하다는 것은 스냅샷 데이터가 내부적으로 일관되다는 뜻입니다. TimeMachine 범위 밖의 데이터가 백업되었다는 뜻은 아닙니다.

### 옮기거나 삭제된 스냅샷 정리

디스크의 스냅샷 디렉터리가 원본이고 SQLite 파일은 다시 만들 수 있는 인덱스입니다. 오래된 스냅샷을 다른 디스크로 옮겼다면 그 경로를 `storage.archive-roots`에 추가하고 `/tmb reload` 후 `/tmb reconcile`을 실행하십시오.

reconcile은 주 저장소와 archive root를 검사해 인덱스를 갱신합니다.

- `storage.root`에서 발견 → `LOCAL`
- archive root에서 발견 → `ARCHIVED`
- 기록은 있으나 어디에도 없음 → `MISSING` (감사와 재발견을 위해 행은 남습니다)

reconcile은 활성 체인도 다시 점검합니다. 체인이 의존하는 스냅샷이 사라졌으면 증분 백업을 차단하고 다음 필터 없는 백업이 새 FULL 기준점을 만듭니다. 사라졌던 스냅샷이 다시 나타나면 — 예를 들어 archive root를 새로 설정한 경우 — reconcile이 기록된 체인을 복구하고 증분 백업을 이어 갑니다. 중간 스냅샷을 건너뛰어 체인을 잇는 일은 없습니다.

`/tmb reconcile`에는 `database.enabled: true`가 필요합니다. reload가 성공한 뒤에는 백그라운드에서 자동으로도 실행됩니다.

### 보존 정리

보존 정리는 기본적으로 꺼져 있습니다. 켜면 개별 스냅샷이 아니라 복원 체인 단위로 동작합니다.

- 활성 체인과 핀이 걸린 스냅샷이 포함된 체인은 보호합니다.
- 항상 `retention.minimum-chains`개 이상의 체인이 남습니다(최소 2이며 그보다 낮게 설정할 수 없습니다).
- 순환, 누락된 부모, 불완전한 FULL 체인, 읽을 수 없는 스냅샷, 심볼릭 링크 등 인벤토리 문제가 있으면 정리 전체를 중단합니다.
- 삭제 전에 남기는 체인의 leaf를 검증합니다.
- 삭제 대상은 먼저 플러그인이 소유한 `retention-trash` 디렉터리로 manifest와 함께 옮기고, 이동에 실패하면 되돌립니다.

`/tmb prune`은 미리보기와 확인 토큰을 출력합니다. `/tmb prune confirm <token>`으로 적용하며, 미리보기 이후 인벤토리가 바뀌었으면 실패합니다. `retention.enabled: true`이면 같은 정책이 성공한 백업 이후 자동으로도 실행됩니다. 안전 검사에 실패하면 백업은 그대로 성공으로 보고되고 아무것도 삭제하지 않습니다.

### 오프라인 복원

복원은 자동으로도, 온라인으로도 실행되지 않습니다. 서버를 정지한 뒤 서버 루트에서 같은 JAR을 실행합니다.

```text
java -jar Timemachine-<version>.jar restore list [--limit <1-1000>]
java -jar Timemachine-<version>.jar restore verify --snapshot <snapshotId>
java -jar Timemachine-<version>.jar restore export --snapshot <snapshotId> --output <새-디렉터리>
java -jar Timemachine-<version>.jar version
```

옵션: 기본 저장소가 아닐 때 `--store <dir>`(기본값은 현재 디렉터리 기준 `plugins/Timemachine/backups`), archive root마다 반복하는 `--archive <dir>`, 명령 어디에나 넣을 수 있는 `--lang en` 또는 `--lang ko`. 종료 코드는 `0` 성공, `1` 입출력·런타임 오류, `2` 사용법 오류, `3` 검증 실패입니다.

`restore export` 동작 순서:

1. 아무것도 건드리기 전에 전체 체인을 검증합니다.
2. FULL 기준점부터 체인을 재생하며 각 스냅샷의 항목과 삭제를 순서대로 적용합니다.
3. 임시 staging 디렉터리에 기록하면서 복원한 파일마다 크기와 SHA-256을 확인합니다.
4. 원본 체인을 다시 검증하고, export 도중 바뀌었으면 결과를 게시하지 않고 중단합니다.
5. 완성된 결과를 요청한 디렉터리로 옮깁니다.

이미 존재하는 경로, 주 저장소나 archive root와 겹치는 경로, 심볼릭 링크가 포함된 경로는 거부합니다. Paper 차원 소스 경로로 기록된 월드는 `<world-root>/dimensions/<namespace>/<dimension>` 구조로 내보내고, 그 외 월드는 월드 이름으로(이름이 이식 가능하지 않거나 중복이면 UUID로) 최상위 디렉터리 하나를 만듭니다. 결과물에는 `restore-worlds.tsv`(월드 UUID → 출력 디렉터리)와 `RESTORE_README.txt`가 포함됩니다.

내보낸 결과를 서버에 설치하는 일은 의도적으로 운영자의 몫입니다. 복구한 서버를 점검할 때까지 원본 월드 디렉터리를 보관하십시오.

## 5. 명령어와 권한

정식 명령은 `/timemachine`입니다. `/tmb`가 권장 짧은 형태이며 게임 내 도움말도 모두 `/tmb`로 표시합니다. `/tm`도 호환을 위해 등록하지만 등록 순서에 따라 다른 플러그인이 선점할 수 있으므로 `/tmb`를 사용하십시오.

| 명령 | 권한 | 비고 |
|---|---|---|
| `/tmb help [advanced\|config\|permissions]` | 없음 | 실행 권한이 있는 항목만 표시 |
| `/tmb backup [world] [--full] [--message <text>]` | `timemachine.backup` | 월드는 이름, NamespacedKey, UUID로 지정. `--message`는 맨 뒤에 두며 이후 전체를 메시지로 사용 |
| `/tmb status` | `timemachine.status` | 실행 상태, 실시간 진행률, 마지막 결과, 다음 예약 실행 |
| `/tmb doctor` | `timemachine.doctor` | 저장소, 여유 공간, 변경 감지, 체인 상태, 데이터베이스, 보존, 월드, 경고 |
| `/tmb history [count]` | `timemachine.history` | `count`는 1–20으로 제한, 기본 5 |
| `/tmb verify <snapshotId>` | `timemachine.verify` | 스냅샷과 전체 체인 검증 |
| `/tmb prune [confirm <token>]` | `timemachine.prune` | 인자 없이 실행하면 미리보기. `retention.enabled` 필요 |
| `/tmb reconcile` | `timemachine.reconcile` | `database.enabled` 필요 |
| `/tmb reload` | `timemachine.reload` | 작업 중에는 거부. 거부된 reload는 기존 런타임을 유지 |

묶음 역할 세 가지를 제공합니다.

| 역할 | 허용 범위 |
|---|---|
| `timemachine.viewer` | `status`, `doctor`, `history` |
| `timemachine.operator` | viewer 범위와 `backup`, `verify` |
| `timemachine.admin` | 모든 TimeMachine 권한 (기본값: op) |

`viewer`와 `operator`는 기본적으로 아무에게도 부여되지 않으므로 권한 플러그인에서 지정하십시오. 개별 `timemachine.*` 노드는 기본값이 op이며 세부 역할 구성에 그대로 사용할 수 있습니다.

backup, verify, prune, reconcile, reload는 한 번에 하나만 실행됩니다. 월드 접근은 서버 스레드에서 이루어지고 스캔, 해시, 복사, SQLite 작업, 검증은 TimeMachine 전용 스레드에서 실행됩니다.

### 언어

명령 출력, 도움말, 진행률, 운영 진단, 보존 정리 결과, 오프라인 CLI를 한국어와 영어로 제공합니다. `language: auto`는 플레이어의 클라이언트 언어를 따르고 콘솔 출력은 서버 JVM 언어를 사용합니다. 모두에게 한 언어를 강제하려면 `language: en` 또는 `language: ko`로 바꾸고 `/tmb reload`를 실행하십시오.

## 6. 설정, 저장소, 네트워크 동작

### 기본 설정 파일

새로 설치하면 대부분의 서버가 실제로 손대는 항목만 기록합니다.

```yaml
config-version: 7

language: auto

backup:
  change-detection: safe

schedule:
  enabled: false
  daily-times:
    - "04:00"
  timezone: "system"
```

나머지 설정은 내장 기본값을 사용하며 필요할 때 파일에 추가하면 됩니다. 전체 키 설명은 [운영 설명서](docs/usage-ko.md)에 있으며 기본값은 다음과 같습니다.

| 키 | 기본값 |
|---|---|
| `storage.root` | `plugins/Timemachine/backups` |
| `storage.archive-roots` | `[]` |
| `database.enabled` | `true` |
| `database.table-prefix` | `tm_` |
| `database.sqlite.file` | `plugins/Timemachine/timemachine-meta.db` |
| `backup.include-worlds` | `[]` (로드된 모든 월드) |
| `backup.scopes` | `region`, `entities`, `poi` |
| `backup.pause-autosave` | `true` |
| `backup.skip-if-no-change` | `true` |
| `backup.copy-threads` | `2` (허용 범위 1–8) |
| `backup.minimum-free-space-mib` | `1024` |
| `schedule.interval-minutes` | `0` (사용 안 함) |
| `schedule.monthly-days` / `schedule.monthly-times` | `[]` (함께 설정해야 함) |
| `schedule.catch-up-on-startup` | `true` |
| `full-backup.enabled` | `false` |
| `retention.enabled` | `false` |
| `retention.max-chains` / `max-age-days` / `minimum-chains` | `8` / `30` / `2` |

상대 경로는 서버 루트를 기준으로 해석합니다. 잘못된 값은 로드나 reload 시점에 해당 키를 지목하며 거부하고, 거부된 reload는 실행 중인 설정을 그대로 둡니다.

일정은 `schedule.timezone`(`system` 또는 `Asia/Seoul` 같은 IANA ID)을 기준으로 실행합니다. 놓친 일정은 일반 실행 중 가장 최근 한 건과 FULL 실행 중 가장 최근 한 건만 대기열에 넣으므로, 오래 정지했다가 켜도 백업이 몰아치지 않습니다.

### 디스크 구조

```text
plugins/Timemachine/
  config.yml
  timemachine-meta.db
  backups/
    state/current-index.tsv          # 추적 파일 인덱스, .bak 사본을 함께 보관
    staging/                         # 진행 중이거나 실패한 스냅샷
    retention-trash/                 # 보존 정리 대기 영역
    snapshots/<year>/<snapshot-dir>/
      files/worlds/<world-uuid>/<scope>/r.<x>.<z>.mca
      snapshot.properties            # id, 부모, 기준점, 종류, 개수, 범위
      worlds.tsv                     # 월드 UUID, key, 이름, 저장 경로, 원본 경로
      entries.tsv                    # 변경 항목의 크기, mtime, SHA-256
      deletions.tsv                  # 이 스냅샷에서 제거된 경로
      checksums.sha256
      restore-notes.txt
```

스냅샷 metadata는 staging 디렉터리를 옮기기 전에 기록하고 fsync하며, 추적 파일 인덱스는 임시 파일과 원자적 교체로 커밋합니다. 스냅샷을 게시한 뒤 인덱스 커밋이 실패하면 해당 스냅샷을 `staging/`으로 격리하고 새 FULL 기준점을 요구합니다.

주 저장소, archive root, SQLite 파일은 서로 겹치거나 월드 디렉터리와 겹칠 수 없습니다. TimeMachine은 시작 시, reload 시, 그리고 모든 백업 직전에 이를 확인하고 겹치면 실행을 거부합니다.

### 데이터베이스

SQLite 파일은 로컬 검색·metadata 인덱스입니다. 기록 조회, 스냅샷 상태, 일정 기준 시각에 사용합니다. 원본은 여전히 스냅샷 디렉터리이며 인덱스는 `/tmb reconcile`로 다시 만들 수 있습니다. `database.enabled: false`로 두면 인덱스 없이 동작하며, `/tmb history`는 스냅샷 파일을 직접 읽고 `/tmb reconcile`은 사용할 수 없습니다.

스키마 버전은 열 때 확인합니다. 더 새로운 TimeMachine이 만든 데이터베이스는 하위 버전으로 변환하지 않고 거부합니다.

### 네트워크 동작

TimeMachine은 외부 네트워크 통신을 하지 않습니다. 소켓을 열지 않고, 원격 서비스에 접속하지 않으며, 텔레메트리나 분석 정보를 보내지 않고, 실행 중에 아무것도 내려받지 않습니다. 원격 데이터베이스도 사용하지 않습니다. SQLite는 JAR에 포함된 드라이버로 접근하는 로컬 파일입니다. 어디에도 업로드하지 않으므로 원격 사본 보관은 운영자의 절차입니다.

## 7. 업그레이드

1. 서버를 정지합니다. JAR을 hot-reload 하지 마십시오.
2. `Timemachine-<old>.jar`를 새 JAR로 교체합니다.
3. 서버를 시작해 콘솔을 확인한 뒤 `/tmb doctor`를 실행합니다.

시작 시 TimeMachine은 내장 기본값에서 빠진 키를 `config.yml`에 병합합니다. 변경이 생기면 먼저 현재 파일을 같은 폴더의 `config.backup-<timestamp>.yml`로 복사하고, 병합한 파일을 원자적으로 기록하고, `config-version`을 올리고, 수행 내용을 기록합니다. 기존 값과 주석은 유지됩니다.

다음 세 개의 구 키는 새 키가 없을 때 자동으로 이전됩니다. `schedule.clock-times` → `schedule.daily-times`, `schedule.run-on-startup-if-missed` → `schedule.catch-up-on-startup`, `backup.max-copy-threads` → `backup.copy-threads`.

다운그레이드는 지원하지 않습니다. `config-version`이 실행 중인 플러그인이 지원하는 값보다 크면 설정을 임의로 해석하지 않고 거부하며 플러그인이 스스로 비활성화됩니다. 되돌릴 가능성이 있다면 `config.backup-*.yml` 파일을 보관하십시오.

구 v1 형식으로 기록된 인덱스나 `.bak` 사본에서 복구한 인덱스는 받아들이되 새 기준점이 필요한 상태로 표시합니다. 다음 필터 없는 백업이 FULL이 됩니다. 업그레이드가 기존 스냅샷을 다시 쓰는 일은 없습니다.

## 8. 범위와 복원 경계

**백업 대상:** 월드 필터를 통과한 로드된 월드의 `region`, `entities`, `poi` 아래 `.mca` 파일.

**백업하지 않는 것:** 플레이어 데이터, `level.dat`, `session.lock`, 월드 아이콘, datapack, 플러그인 데이터와 설정, 서버 설정, 로그, JAR 등 서버의 나머지 전부. 로드되지 않은 월드도 백업하지 않습니다.

**제공하지 않는 기능:** 온라인·즉시 복원, 자동 월드 교체, 압축, 암호화, 중복 제거, 클라우드·원격 업로드, webhook, 외부 모니터링, GUI, Folia 지원, hot reload.

**복원 경계.** 체인 검증이 알려 주는 것은 스냅샷 데이터가 내부적으로 일관되고 FULL 기준점 기준으로 완전하다는 사실입니다. 복사 시점의 월드가 일관된 상태였는지, 서버의 나머지를 복구할 수 있는지, 스냅샷을 담은 저장 장치가 건강한지는 알려 주지 않습니다. 지역 데이터만 되돌리면 함께 복원하는 플레이어 데이터·`level.dat`와 어긋날 수 있으므로 함께 계획하십시오. 검증된 사본을 최소 한 벌은 다른 하드웨어에 원격으로 보관하십시오. TimeMachine은 설정된 경로에만 기록하며 장비 자체를 잃는 상황은 막지 못합니다.

## 9. 문제 해결

| 증상 | 원인과 조치 |
|---|---|
| `/tm`이 다른 플러그인을 실행 | 별칭 충돌. `/tmb` 또는 `/timemachine`을 사용하십시오. |
| `TimeMachine storage is still initializing` | 저장소와 SQLite가 백그라운드에서 시작 중입니다. 잠시 후 다시 시도하십시오. |
| `TimeMachine is not initialized` | 시작에 실패했습니다. 콘솔에서 거부된 설정을 확인하고 `config.yml`을 고친 뒤 `/tmb reload`를 실행하십시오. |
| 시작 시 플러그인이 스스로 비활성화 | 저장 경로가 월드나 다른 root와 겹치거나, `config.yml`이 잘못되었거나, `config-version`이 플러그인보다 높습니다. 콘솔에 이유가 표시됩니다. |
| `doctor`가 `FULL required` 표시 | 신뢰할 기준점이 없습니다. 월드 인자 없이 `/tmb backup`을 실행하십시오. |
| `doctor`가 체인 `broken` 표시 | 활성 체인의 스냅샷을 읽을 수 없거나 사라졌습니다. 복구하거나 연결한 뒤 `/tmb reconcile`을 실행하거나, 필터 없는 `/tmb backup`으로 새 체인을 시작하십시오. |
| 필터 있는 백업이 거부됨 | 전역 기준점이 먼저 필요하거나 월드 저장 레이아웃이 바뀌었습니다. 필터 없는 `/tmb backup`을 실행하십시오. |
| `Insufficient free space` | 사용 가능 공간이 `backup.minimum-free-space-mib`와 예상 복사 크기의 합보다 적습니다. 공간을 확보하거나 정리하거나 예비 값을 낮추십시오. |
| `Previously tracked world is not loaded` | 추적하던 월드가 서버에 없습니다. 로드하거나 `backup.include-worlds`를 명시해 바뀐 월드 구성을 확인하십시오. |
| `SQLite metadata index is disabled` | `database.enabled`가 `false`입니다. `/tmb reconcile`에는 필요합니다. |
| 옮긴 스냅샷이 `MISSING`으로 표시 | 해당 경로를 `storage.archive-roots`에 추가하고 `/tmb reload` 후 `/tmb reconcile`을 실행하십시오. |
| prune 토큰 불일치 | 미리보기 이후 인벤토리가 바뀌었습니다. `/tmb prune`을 다시 실행하십시오. |
| 백업 후 `Retention warning` | 자동 보존 정리가 안전 검사에서 중단되었습니다. 백업은 성공했고 삭제된 것은 없습니다. 이유는 `/tmb prune`으로 확인하십시오. |
| reload 거부 | 후보 설정이 검증을 통과하지 못했습니다. 기존 런타임이 계속 동작하며 자세한 내용은 콘솔에 있습니다. |
| export가 출력 경로를 거부 | 대상이 이미 존재하거나, 백업 저장소와 겹치거나, 심볼릭 링크를 포함합니다. 다른 위치의 새 디렉터리를 사용하십시오. |

## 10. 지원과 라이선스

- [운영 설명서](docs/usage-ko.md) — 전체 설정 항목과 복원 훈련 절차
- [아키텍처와 안전 경계](docs/architecture-ko.md) — 저장 형식, 커밋 순서, 실패 동작
- [SAFE/FAST 벤치마크](docs/change-detection-benchmark.ko.md)
- [변경 기록](CHANGELOG.md) · [기여 안내](CONTRIBUTING.md) · [보안 정책](SECURITY.md)

버그 신고와 기능 요청은 이 저장소의 이슈 트래커를 이용하십시오. 보안에 영향이 있는 사안은 공개 이슈 대신 [SECURITY.md](SECURITY.md)의 절차를 따르십시오.

### 소스에서 빌드

```text
./gradlew clean build --console=plain
```

Windows에서는 `.\gradlew.bat`를 사용합니다. 배포 JAR은 `build/libs/Timemachine-<version>.jar`에 생성됩니다.

### 라이선스

TimeMachine은 [MIT License](LICENSE)로 배포됩니다. 포함된 서드파티 구성 요소와 라이선스는 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)에 정리되어 있습니다.

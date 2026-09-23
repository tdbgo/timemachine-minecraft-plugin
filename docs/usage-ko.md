# TimeMachine 사용법

TimeMachine by PLAYCITY BLOCK

한국어 · [English](usage.md)

TimeMachine은 Paper 월드의 `region`, `entities`, `poi` `.mca` 파일을 FULL 및 증분 snapshot으로 저장하는 플러그인입니다. 서버 전체 백업 도구가 아니므로 별도의 원격 백업과 복원 훈련을 함께 운영해야 합니다.

## 요구 사항

- `26.2` API 계열 Paper 서버. `plugin.yml`의 `api-version`은 `'26.2'`이며 `paper-api 26.2.build.111-stable`로 빌드합니다.
- Java 25 이상 (`--release 25`로 컴파일).
- Folia 미지원.

## 설치

1. 릴리스 JAR을 서버의 `plugins` 폴더에 넣습니다.
2. 서버를 정상적으로 시작합니다.
3. 안전한 기본값으로 자동 초기화되면 `/tmb backup`을 실행합니다. 첫 전역 백업은 자동으로 FULL이 됩니다.
4. `/tmb status`와 `/tmb doctor`로 결과를 확인합니다.
5. 자동 백업이 필요하면 `plugins/Timemachine/config.yml`의 `schedule.enabled`를 켜고 `/tmb reload`를 실행합니다.
6. 생성된 snapshot ID로 `/tmb verify <snapshotId>`를 실행합니다.

PlugMan 같은 hot reload 도구와 Paper/Bukkit의 reload 명령은 사용하지 마십시오.

## 언어

기본 `language: auto`는 각 플레이어의 Minecraft 클라이언트 언어를 따르며, 콘솔 명령은 서버 JVM 언어를 사용합니다. 전체 출력을 한국어 또는 영어로 고정하려면 다음 중 하나를 설정하고 `/tmb reload`를 실행하십시오.

```yaml
language: ko # 또는 en
```

## 명령어

```text
/timemachine help [advanced|config|permissions]
/timemachine backup
/timemachine backup <world>
/timemachine backup --full
/timemachine backup <world> --full
/timemachine backup --message <text>
/timemachine status
/timemachine doctor
/timemachine history [count]
/timemachine verify <snapshotId>
/timemachine prune [confirm <token>]
/timemachine cleanup [confirm <token>]
/timemachine reconcile
/timemachine reload
```

- `<world>`에는 월드 이름, NamespacedKey 또는 UUID를 사용할 수 있습니다.
- `--message` 뒤의 나머지 인자는 모두 메시지로 저장되므로 마지막 옵션으로 사용하십시오.
- `history`의 조회 개수는 1~20으로 제한됩니다.
- 정식 명령은 `/timemachine`이며 충돌 없는 권장 짧은 별칭은 `/tmb`입니다. 기존 `/tm`은 하위 호환을 위해 유지하지만 FAWE 같은 다른 플러그인이 선점할 수 있습니다.
- 인자 없이 `/tmb`를 실행하면 현재 권한으로 사용할 수 있는 빠른 시작 명령만 표시합니다.

### 권한을 간단하게 부여하기

| 역할 | 허용 범위 |
|---|---|
| `timemachine.viewer` | status, doctor, history |
| `timemachine.operator` | viewer 범위, backup, verify |
| `timemachine.admin` | 모든 TimeMachine 명령 |

OP는 별도 설정 없이 모든 명령을 사용할 수 있습니다. 세부 역할이 필요할 때만 기존 `timemachine.*` 개별 권한을 사용하십시오.

## 주요 설정

새 설치에서 생성되는 기본 파일은 언어, 변경 감지와 자동 일정처럼 일상적인 선택에 집중합니다.

```yaml
language: auto

backup:
  change-detection: safe

schedule:
  enabled: false
  daily-times:
    - "04:00"
  timezone: "system"
```

나머지 항목은 아래의 내장 기본값으로 동작하며, 필요할 때만 직접 추가하면 됩니다. 기존 고급 설정 파일은 자동으로 축약되거나 값이 삭제되지 않습니다.

### 저장소

```yaml
storage:
  root: plugins/Timemachine/backups
  archive-roots: []
```

- `storage.root`: 새 snapshot, staging과 현재 인덱스를 기록할 기본 경로입니다.
- `storage.archive-roots`: 관리자가 외부로 옮긴 snapshot을 백업 체인 검사, verify, restore와 reconcile에서 검색할 추가 경로입니다. 플러그인이 자동으로 archive로 이동하지는 않습니다.
- 기본 경로와 archive 경로는 서로 겹치면 안 되며, 어느 경로도 월드 디렉터리를 포함하거나 그 안에 들어가면 안 됩니다.
- snapshot을 옮길 때는 `snapshot.properties`를 포함한 snapshot 디렉터리 전체를 이동하십시오. 가능하면 활성 체인이 아닌 완전한 이전 체인 단위로 옮기고, archive 경로를 먼저 설정한 뒤 검증하십시오.

### SQLite metadata

```yaml
database:
  enabled: true
  table-prefix: tm_
  sqlite:
    file: plugins/Timemachine/timemachine-meta.db
```

SQLite는 history와 snapshot 위치 상태를 관리하는 보조 인덱스입니다. snapshot 파일이 원본이며 `/timemachine reconcile`로 인덱스를 다시 맞출 수 있습니다. 기본 저장소에서 발견하면 `LOCAL`, archive에서 발견하면 `ARCHIVED`, 어느 검색 경로에도 없으면 `MISSING`으로 갱신합니다. 감사와 재발견을 위해 `MISSING` 행을 DB에서 삭제하지 않습니다. 결과의 `새 누락`은 이번 실행에서 처음 누락 처리된 수이고, `전체 누락`은 이전 실행에서 이미 누락 처리된 항목까지 포함한 현재 총수입니다.

SQLite를 열 수 없어도 백업과 파일 기반 history는 계속 사용할 수 있습니다. `doctor`가 제한 상태를 표시하며, DB를 복구하고 TimeMachine을 reload 또는 재시작하면 reconcile을 다시 사용할 수 있습니다.

`reconcile`은 현재 인덱스가 가리키는 FULL부터 마지막 snapshot까지의 부모 관계도 함께 검사합니다. 이동본이 archive에서 발견되면 증분 백업을 계속하고, 활성 체인의 snapshot이 실제로 사라졌다면 증분을 차단합니다. 이 경우 다음 월드 필터 없는 수동 또는 예약 백업만 새 FULL 기준점으로 승격됩니다. 삭제된 증분 snapshot을 건너뛰어 체인을 이어 붙이지는 않습니다.

`table-prefix`는 영문자 또는 `_`로 시작하고 영문자, 숫자, `_`만 포함해야 합니다.

### 백업 대상과 안전 옵션

```yaml
backup:
  include-worlds: []
  scopes:
    - region
    - entities
    - poi
  pause-autosave: true
  skip-if-no-change: true
  change-detection: safe
  copy-threads: 2
  minimum-free-space-mib: 1024
```

- `include-worlds: []`: 현재 로드된 모든 월드를 대상으로 합니다. 목록을 지정하면 이름, NamespacedKey 또는 UUID가 일치하는 월드만 사용합니다.
- `scopes`: `region`, `entities`, `poi` 중 하나 이상이어야 합니다. 일관된 복원을 위해 세 scope를 모두 권장합니다.
- `pause-autosave`: 저장 및 복사 구간에 autosave를 일시 중지합니다. `false`로 설정하면 복사 도중 파일이 다시 저장될 수 있어 snapshot 일관성이 낮아집니다.
- `skip-if-no-change`: 변경과 삭제가 없으면 빈 증분 snapshot을 만들지 않습니다.
- `change-detection`: 기본 `safe`는 모든 추적 파일의 SHA-256을 비교하여 크기와 수정 시각이 같은 변경도 감지합니다. `fast`는 수정 시각과 크기만 비교하므로 이러한 변경을 놓칠 수 있습니다.
- `copy-threads`: 1~8 범위의 bounded 복사 스레드 수입니다.
- `minimum-free-space-mib`: 예상 복사 용량에 더해 남겨 둘 최소 여유 공간입니다.

### 증분 일정

```yaml
schedule:
  enabled: false
  interval-minutes: 0
  daily-times:
    - "04:00"
  monthly-days: []
  monthly-times: []
  timezone: "system"
  catch-up-on-startup: true
```

- `interval-minutes`: 서버가 켜진 동안 마지막 interval 실행을 기준으로 반복합니다. `0`이면 비활성입니다.
- `daily-times`: 매일 실행할 `HH:mm` 시각입니다.
- `monthly-days`와 `monthly-times`: 둘 다 비우거나 둘 다 값을 지정해야 합니다. 해당 날짜가 없는 달은 건너뜁니다.
- `enabled: true`이면 interval, daily 또는 monthly 중 하나 이상의 실제 실행 조건이 있어야 합니다. 실행 조건이 없으면 시작과 reload를 거부합니다.
- `timezone`: `system`은 서버 운영체제 시간대를 사용합니다. `Asia/Seoul` 같은 IANA Zone ID도 사용할 수 있으며 잘못된 값은 reload를 거부합니다.
- `catch-up-on-startup`: 종류와 관계없이 최근 커밋된 스냅샷 이후 서버가 꺼져 있는 동안 놓친 가장 최근 일정 한 건을 시작 후 대기열에 넣습니다.
- VM 일시중지나 긴 시스템 정지 뒤에는 놓친 regular 일정과 FULL 일정에서 각각 가장 최근 한 건만 대기열에 넣어 연속 백업 폭주를 방지합니다.

### 정기 FULL 일정

```yaml
full-backup:
  enabled: false
  monthly-days:
    - 1
  monthly-times:
    - "03:00"
  catch-up-on-startup: true
```

`enabled: true`이면 날짜와 시각을 모두 하나 이상 지정해야 합니다. 월드 필터 없는 FULL만 새 전역 base가 됩니다.

### 체인 보존 정책

```yaml
retention:
  enabled: false
  max-chains: 8
  max-age-days: 30
  minimum-chains: 2
  pinned-snapshots: []
```

- 기본값은 비활성입니다.
- 정리 단위는 개별 snapshot이 아니라 FULL 기준점부터 마지막 자식까지의 완전한 복원 체인입니다.
- 현재 체인과 `pinned-snapshots` 중 하나라도 포함한 체인은 보호합니다.
- `minimum-chains`는 2보다 작게 설정할 수 없습니다.
- 활성화하면 성공한 백업 뒤에 정책을 적용합니다. `/timemachine prune`으로 미리보기를 만들고 표시된 토큰을 확인 명령에 전달해 수동 실행할 수도 있습니다.
- 확인 단계에서는 snapshot 목록이 바뀌지 않았는지 확인하고 남길 모든 체인을 검증합니다. 안전 검사가 실패하면 아무것도 삭제하지 않습니다.

## snapshot 구조

```text
plugins/Timemachine/backups/snapshots/<year>/<snapshot-directory>/
  files/worlds/<world-uuid>/<scope>/r.<x>.<z>.mca
  snapshot.properties
  worlds.tsv
  entries.tsv
  deletions.tsv
  checksums.sha256
  restore-notes.txt
```

- `snapshot.properties`: snapshot 종류, 부모, FULL base, 생성 시각과 통계
- `worlds.tsv`: UUID 경로와 월드 key/name/source path 매핑
- `entries.tsv`: 이 snapshot에서 복사한 파일과 SHA-256
- `deletions.tsv`: 부모 이후 사라진 파일
- `checksums.sha256`: 복사 파일용 표준 checksum 목록

snapshot ID는 `snapshots/` 기준 상대 경로이므로 연도 디렉터리를 포함합니다. 예를 들어 `2026/2026-08-16_04-00-00-123_manual_1a2b3c4d` 형태이며 시각은 `schedule.timezone`을 따릅니다.

## 검증과 복원

`/timemachine verify <snapshotId>`는 FULL base까지 전체 체인을 읽고 경로, 매핑, 파일 크기, SHA-256, 항목 수와 부모 관계를 확인합니다.

복원은 반드시 서버를 완전히 종료한 복사본 환경에서 수행하십시오. 권장 방식은 JAR에 포함된 오프라인 export입니다.

```powershell
java -jar plugins/Timemachine-<version>.jar restore list
java -jar plugins/Timemachine-<version>.jar restore verify --snapshot <snapshotId>
java -jar plugins/Timemachine-<version>.jar restore export --snapshot <snapshotId> --output <새-디렉터리>
```

CLI를 한국어로 사용하려면 명령 어디에나 `--lang ko`를 추가합니다. 영어는 `--lang en`입니다.

기본 저장소가 아니라면 `--store <경로>`를 지정하고, 옮겨 둔 체인은 `--archive <경로>`를 반복 지정합니다. `restore list`는 `--limit <1-1000>`(기본 20)도 받습니다. 종료 코드는 `0` 성공, `1` 입출력·런타임 오류, `2` 사용법 오류, `3` 검증 실패입니다. export는 전체 체인을 재검증한 뒤 새 출력 디렉터리에 최종 상태를 구성하며, 기존 경로·저장소와 겹치는 경로·심볼릭 링크는 거부합니다. `worlds.tsv`의 source path가 `/dimensions/<namespace>/<dimension>`으로 끝나고 월드 key와 일치하면 `<world-root>/dimensions/<namespace>/<dimension>` 구조로 내보내고, 그 밖의 월드는 월드 이름(이식 가능하지 않거나 중복이면 UUID)으로 최상위 디렉터리 하나를 만듭니다.

1. 서버 종료 전에 verify를 실행합니다.
2. 서버를 완전히 종료하고 export를 만듭니다.
3. export의 `RESTORE_README.txt`와 `restore-worlds.tsv`를 검토합니다.
4. 기존 월드를 보존한 채 복사본 환경에 export 결과를 설치합니다.
5. 복원 서버를 시작해 청크, 엔티티와 POI를 확인합니다.

Paper 업그레이드로 실제 월드 저장 경로가 바뀌면 플러그인은 다음 필터 없는 수동 또는 예약 백업을 새 전역 FULL 기준점으로 자동 승격합니다. 기존 증분 체인과 metadata는 수정하지 않으며, 새 FULL이 성공한 뒤부터 그 체인에서 증분 백업을 이어 갑니다. 월드 필터가 있는 백업은 완전한 기준점을 만들 수 없으므로 거부합니다.

TimeMachine은 온라인 또는 자동 in-place restore 명령을 제공하지 않습니다. 운영 월드에 snapshot 파일을 직접 덮어쓰지 마십시오.

## 상태와 진단

- `/timemachine status`: 현재 작업 단계, 파일·바이트 진행률, 경과 시간, 마지막 백업과 다음 예약을 표시합니다.
- `/timemachine doctor`: 저장소 쓰기 가능 여부와 여유 공간, 변경 감지 모드, 현재 체인, 보호 scope, SQLite, FULL 일정, retention과 실패 staging 위험을 점검합니다.
- SAFE/FAST의 실제 측정 조건과 결과는 [변경 감지 벤치마크](change-detection-benchmark.ko.md)를 참고하십시오.

## 운영 주의 사항

- retention 사용 여부와 무관하게 검증된 별도 원격 보관본을 유지하십시오.
- `change-detection: fast`를 선택하면 크기와 수정 시각이 같은 변경을 놓칠 수 있습니다. 무결성 우선 운영은 `safe`를 사용하십시오.
- 플레이어 데이터, `level.dat`, datapack, 플러그인 데이터와 서버 설정은 별도로 백업해야 합니다.
- snapshot을 수동 이동/삭제했다면 `/timemachine reconcile`을 실행하십시오.
- 다른 저장소로 이동했다면 먼저 `storage.archive-roots`를 추가하고 `/timemachine reload`를 성공시킨 뒤 `/timemachine reconcile`과 최신 leaf의 `verify`를 실행하십시오.
- 공간 정리는 개별 snapshot 수동 삭제보다 완전한 비활성 체인만 제거하는 `/timemachine prune`을 권장합니다.
- 실패 표시가 있는 staging은 `/timemachine cleanup`으로 미리본 뒤 정리하십시오. 진행 중인 staging은 대상에서 제외됩니다.
- 설정 reload가 실패하면 기존 런타임은 유지됩니다. 콘솔의 구체적인 검증 오류를 수정한 뒤 다시 시도하십시오.

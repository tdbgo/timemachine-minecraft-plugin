# TimeMachine 아키텍처와 안전 경계

한국어 · [English](architecture.md)

이 문서는 현재 구현을 기준으로 TimeMachine의 저장 형식, 스레드 경계, 커밋 순서와 실패 처리 원칙을 설명합니다. 계획 중인 기능을 현재 기능처럼 기술하지 않습니다.

## 백업 범위

TimeMachine은 로드된 Paper 월드마다 아래 세 디렉터리에서 `.mca` 파일만 다룹니다.

- `region`: 청크의 블록과 블록 엔티티
- `entities`: 엔티티 데이터
- `poi`: 주민 작업대 등 POI 데이터

월드 UUID가 영속 식별자이며, 월드 이름과 NamespacedKey는 표시 및 복원 매핑 정보로 저장합니다. `level.dat`, 플레이어 데이터, datapack, 플러그인 데이터와 일반 서버 설정은 범위 밖입니다.

## 실행 경계

Paper 서버 스레드에서는 다음 작업만 수행합니다.

- 대상 월드 목록과 경로 확정
- autosave 상태 저장 및 필요 시 일시 중지
- `world.save(true)` 호출
- autosave 상태 복원

다음 작업은 플러그인 소유 작업 스레드에서 수행합니다.

- 디렉터리 스캔, 안전 모드의 파일 해시 비교 또는 빠른 모드의 메타데이터 비교
- `.mca` 복사와 SHA-256 계산
- manifest와 현재 인덱스 기록
- SQLite 초기화, 조회, 기록과 reconcile
- 스냅샷 체인 검증

플러그인 활성화 시 저장소와 SQLite 초기화도 백그라운드에서 실행됩니다. 초기화가 끝나기 전 명령은 데이터를 변경하지 않고 대기 안내를 반환합니다.

## 책임 분리

- `SnapshotStore`: 스냅샷 생성·커밋, 기록 조회와 위치 검색
- `SnapshotVerifier`: 체인, 월드 매핑, 변경·삭제 목록, checksum과 실제 파일 검증
- `SnapshotChainHealth`: 기본 및 archive 저장소를 대상으로 활성 체인의 부모·FULL 기준점 연결을 빠르게 검사한 결과
- `BackupFilePipeline`: region 파일 검색, SAFE/FAST 변경 선별, bounded 복사와 여유 공간 검사
- `BackupPlanning`: FULL·증분 계획, 월드 식별자와 저장 경로 연속성 판단
- `BackupMaintenanceService`: 검증, reconcile, retention과 검색용 metadata 처리
- `BackupRuntimeContext`: 활성 저장소·인덱스·복사 executor의 수명주기 소유
- `MessageCatalog`: 영어·한국어 메시지 로딩, 플레이어/콘솔 언어 선택과 자리표시자 치환
- `TimeMachineCommand`와 `TimeMachineCli`: 서버 명령 및 오프라인 복원 인터페이스

저장·무결성 검증 오류는 언어 중립적인 메시지 키와 인자로 보관합니다. 게임 명령과 오프라인 CLI는 이를 선택한 영어 또는 한국어로 변환하며, 경로·스냅샷 ID·기대값처럼 복구 진단에 필요한 값은 그대로 유지합니다.

## 백업 트랜잭션

백업은 동시에 하나만 실행되며 다음 순서를 따릅니다.

1. 현재 인덱스와 요청을 이용해 `FULL`, `INCREMENTAL`, `SCOPED_FULL` 중 하나를 결정합니다.
2. 서버 스레드에서 월드 식별자, 저장 경로와 autosave 상태를 확정합니다.
3. 필요한 월드의 autosave를 중지하고 `world.save(true)`를 호출합니다.
4. staging 디렉터리를 만들고 지정된 scope를 스캔합니다.
5. FULL은 모든 대상 파일을 복사합니다. INCREMENTAL의 기본 `safe` 모드는 모든 추적 파일을 안정적으로 해시해 이전 SHA-256과 비교하고, 선택 가능한 `fast` 모드는 `mtime + size`를 비교합니다.
6. 복사 전후의 크기·수정 시각·파일 식별자가 달라지면 최대 세 번 다시 복사합니다.
7. 모든 복사가 끝나면 autosave를 원래 값으로 복원합니다.
8. staging에 snapshot metadata를 쓰고, 검증 가능한 파일 해시와 삭제 항목을 기록합니다.
9. staging 디렉터리를 최종 snapshots 경로로 이동합니다.
10. 마지막으로 `state/current-index.tsv`를 임시 파일과 원자적 교체 방식으로 커밋합니다.
11. SQLite가 켜져 있으면 검색용 metadata를 기록합니다. 이 단계가 실패해도 스냅샷과 현재 인덱스가 이미 안전하게 커밋됐다면 백업 데이터는 유지됩니다.

스냅샷 이동 후 현재 인덱스 커밋이 실패하면 새 스냅샷을 staging의 실패 격리 경로로 이동합니다. 격리할 수 없는 경우 현재 런타임은 다음 백업에 새 전역 FULL 기준점을 요구합니다.

Paper 업그레이드로 동일 UUID 월드의 실제 저장 경로가 바뀌면 필터 없는 백업 계획을 새 전역 FULL로 자동 승격합니다. 새 FULL의 snapshot과 현재 인덱스가 모두 커밋되기 전에는 기존 체인이나 metadata를 수정하지 않습니다. 필터가 있는 요청은 전역 기준점의 완전성을 보장할 수 없어 거부합니다.

## 스냅샷 종류

- `FULL`: 필터가 없는 전역 기준점입니다. 부모가 없고 자기 자신을 base로 가리킵니다.
- `INCREMENTAL`: 직전 커밋을 부모로 가리키며 변경 및 삭제된 항목만 기록합니다.
- `SCOPED_FULL`: 특정 월드에 대해 모든 파일을 복사하지만 전역 기준점은 아닙니다. 기존 체인의 자식으로 남습니다.

호환되지 않는 이전 인덱스, 손상된 주 인덱스에서 복구한 상태, 또는 기본·archive 저장소 어디에서도 찾을 수 없는 활성 체인 snapshot을 발견하면 증분 백업을 차단하고 월드 필터 없는 다음 백업을 전역 FULL로 승격합니다. archive에 온전한 체인이 다시 나타난 뒤 reconcile하면 기존 current-index의 추적 정보를 보존한 채 증분 백업을 재개할 수 있습니다.

## 저장 구조

```text
plugins/Timemachine/
  config.yml
  timemachine-meta.db
  backups/
    state/
      current-index.tsv
      current-index.tsv.bak
    staging/
      <작업 또는 실패 격리 디렉터리>/
    retention-trash/
      <삭제 전 원자적 이동에 사용하는 플러그인 소유 디렉터리>/
    snapshots/
      <year>/
        <snapshot-directory>/
          files/
            worlds/<world-uuid>/<scope>/r.<x>.<z>.mca
          snapshot.properties
          worlds.tsv
          entries.tsv
          deletions.tsv
          checksums.sha256
          restore-notes.txt
```

설정에 따라 SQLite 파일과 backup root는 다른 위치를 사용할 수 있습니다. snapshot ID는 `snapshots/` 기준 상대 경로이므로 연도 디렉터리를 포함합니다(예: `2026/2026-08-16_04-00-00-123_manual_1a2b3c4d`).

## 무결성 검증

`/timemachine verify <snapshotId>`는 target에서 FULL base까지 부모를 역추적한 후 다음을 확인합니다.

- 체인 순환, 누락된 부모와 서로 다른 base 참조
- FULL의 부모/base 규칙
- snapshot format version
- `worlds.tsv`의 UUID와 storage path 일관성
- 변경/삭제 항목의 중복, 경로 이탈과 world/scope/region 좌표 일관성
- 실제 파일 크기와 SHA-256
- `entries.tsv`와 `checksums.sha256`의 일치
- metadata에 기록된 항목 수

심볼릭 링크는 snapshot 파일로 인정하지 않습니다. 검증 성공은 저장된 체인의 내부 무결성을 의미하며, 서버 전체 백업이나 실제 복원 성공을 보장하지는 않습니다.

## 오프라인 복원 export

배포 JAR의 `restore export`는 Bukkit/Paper 런타임을 시작하지 않는 순수 Java 경로입니다.

1. target에서 FULL까지 전체 체인을 검증합니다.
2. 검색 가능한 저장소를 한 번 조사해 검증된 체인의 모든 실제 위치를 고정합니다.
3. 체인의 변경·삭제 항목을 순서대로 합쳐 target 시점의 최종 파일 집합을 계산합니다.
4. `worlds.tsv`의 source path가 해당 월드 NamespacedKey에서 만든 `/dimensions/<namespace>/<dimension>` suffix로 끝날 때만 `<world-root>/dimensions/<namespace>/<dimension>` 상대 경로를 구성합니다. 절대 source path 자체는 출력 경로로 사용하지 않습니다.
5. 출력과 저장소의 경로 중첩, 기존 출력, 심볼릭 링크를 거부합니다.
6. 출력 부모 아래 임시 디렉터리에 복사하고 각 파일의 크기와 SHA-256을 다시 확인합니다.
7. 모든 작업이 성공한 뒤에만 임시 디렉터리를 최종 출력 이름으로 이동합니다.

운영 월드에 직접 쓰거나 기존 디렉터리를 자동 교체하는 경로는 없습니다.

## 체인 단위 retention

retention은 기본 비활성이고 FULL 기준점별 완전한 체인만 대상으로 삼습니다.

- 부모 누락, 순환, 중복 ID, 알 수 없는 형식 또는 심볼릭 링크가 하나라도 있으면 전체 prune을 거부합니다.
- 현재 인덱스가 참조하는 체인과 핀 지정 체인을 보호합니다.
- 최소 2개 체인을 남기며 개별 증분 snapshot은 삭제하지 않습니다.
- 수동 prune은 계획 token과 전체 inventory fingerprint를 사용하므로 계획 뒤 snapshot이 추가·변경되면 확인을 거부합니다.
- 삭제 전에 남길 체인의 모든 leaf를 검증합니다.
- 삭제 대상은 먼저 같은 저장소의 plugin-owned retention trash로 이동합니다. 이동 도중 실패하면 이미 옮긴 항목을 원래 위치로 되돌립니다.
- trash 제거가 실패하면 경로를 보고하고 데이터는 복구 가능한 상태로 남깁니다.

## SQLite의 역할

SQLite는 snapshot history와 저장 위치 상태를 빠르게 조회하기 위한 보조 인덱스입니다.

- `LOCAL`: 기본 snapshots root에서 발견
- `ARCHIVED`: 설정된 archive root에서 발견
- `MISSING`: DB에는 있으나 어느 검색 경로에서도 발견되지 않음

스냅샷 디렉터리가 원본이며 SQLite DB는 재생성할 수 있습니다. reconcile은 삭제된 행을 제거하지 않고 `MISSING`으로 남겨 이동본 재발견과 감사가 가능하게 합니다. 같은 ID가 archive에서 다시 발견되면 `ARCHIVED`와 실제 경로로 갱신합니다. schema upgrade는 트랜잭션으로 처리하고, 현재 코드보다 높은 schema version을 발견하면 DB를 낮은 버전으로 덮어쓰지 않고 초기화를 거부합니다.

## 설정 교체와 종료

`/timemachine reload`는 기존 런타임을 먼저 파기하지 않습니다. 후보 설정과 저장소를 백그라운드에서 완전히 초기화한 뒤 성공한 경우에만 활성 런타임을 교체합니다. 실패하면 기존 설정과 런타임을 유지합니다.

종료 시 새 작업을 막고, 실행 중 작업과 복사 스레드를 중단하며, autosave 상태를 복원하고 플러그인 소유 executor를 종료합니다. `/reload`, PlugMan 계열 hot reload와 온라인 restore는 지원하지 않습니다.

## 의도적으로 지원하지 않는 기능

- 온라인 또는 자동 restore
- 압축, 암호화, object deduplication
- S3 등 원격 저장소 전송
- Discord/Webhook 알림과 외부 모니터링
- Folia region scheduler

이 기능들은 데이터 삭제 권한, 원격 자격 증명, 새로운 일관성 모델이 필요하므로 별도 설계와 회귀 검증 없이 현재 코어에 추가하지 않습니다.

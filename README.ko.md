# TimeMachine

**Paper를 위한 검증 가능한 지역 데이터 백업.**

TimeMachine by PLAYCITY BLOCK.

한국어 · [English](README.md)

![TimeMachine 백업 타임라인](assets/timemachine-cover.png)

TimeMachine은 Paper 월드의 지역 데이터를 FULL·증분 스냅샷으로 보관합니다. 복사 파일의 SHA-256, 복원 체인 검증, JAR에 내장된 오프라인 export를 통해 실제로 확인할 수 있는 복원을 지향합니다.

## 빠른 시작

요구 사항은 Paper 26.2 build 111 이상 26.2 계열과 Java 25입니다.

1. `Timemachine-<version>.jar`를 서버의 `plugins` 폴더에 넣습니다.
2. 서버를 정상 시작합니다. 별도 설치 마법사 없이 안전한 기본값으로 바로 준비됩니다.
3. `/tmb backup`을 실행합니다. 첫 전역 백업은 자동으로 FULL 기준점이 됩니다.
4. `/tmb status`와 `/tmb doctor`로 결과를 확인합니다.
5. 매일 자동 백업이 필요하면 생성된 설정에서 `schedule.enabled`만 켜고 `/tmb reload`를 실행합니다.

기본 설정은 일상적으로 바꿀 항목만 보여 줍니다.

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

저장소, SQLite, scope, 복사 스레드, retention과 고급 일정은 안전한 내장 기본값을 사용합니다. 기존 고급 설정 키도 계속 지원합니다.

## 한국어와 영어

명령어, 도움말, 진행률, 운영 진단, 보존 정리 결과와 오프라인 CLI를 한국어와 영어로 제공합니다. `language: auto`는 플레이어의 클라이언트 언어를 따르고, 콘솔 명령은 서버 JVM 언어를 사용합니다. 모든 사용자에게 한 언어를 강제하려면 `language: ko` 또는 `language: en`으로 바꾸고 `/tmb reload`를 실행하십시오.

## 평소 사용하는 명령어

```text
/tmb backup
/tmb status
/tmb doctor
/tmb help
```

정식 명령은 `/timemachine`이며 충돌 없는 권장 짧은 별칭은 `/tmb`입니다. 기존 `/tm`도 하위 호환을 위해 유지하지만, FAWE 같은 다른 플러그인이 서버의 명령 등록 순서에 따라 선점할 수 있으므로 플러그인을 함께 사용할 때는 `/tmb`를 사용하십시오.

기록 조회, 검증, 정리, reconcile, reload, 월드 지정, FULL 요청과 메모는 `/tmb help advanced`에서 확인할 수 있습니다.

## SAFE와 FAST 비교

| 모드 | 변경 없는 파일 스캔 | 크기·수정 시각이 유지된 내용 변경 감지 | 권장 용도 |
|---|---|---|---|
| `safe` | 모든 파일을 읽고 해시 비교 | 가능 | 기본값, 무결성 우선 운영 |
| `fast` | 파일 크기와 수정 시각만 비교 | 불가능 | 스캔 시간이 실제 병목인 대형 저장소 |

실제로 복사하는 파일은 두 모드 모두 SHA-256으로 검증합니다. 과거 로컬 Java 21 warm-cache 측정에서는 FAST가 변경 파일 선별 단계에서 4.3~16.8배 빨랐으며, Java 21은 이번 릴리스의 지원 런타임이 아닙니다. 전체 백업 속도 보장 수치가 아니며 자세한 조건은 [측정 방법과 결과](docs/change-detection-benchmark.md)를 참고하십시오.

## 복원

복원 훈련 전 서버를 완전히 종료하고 같은 JAR을 명령줄에서 실행합니다.

```powershell
java -jar Timemachine-<version>.jar restore list
java -jar Timemachine-<version>.jar restore verify --snapshot <snapshotId>
java -jar Timemachine-<version>.jar restore export --snapshot <snapshotId> --output <새-디렉터리>
```

CLI 출력 언어는 명령 어디에나 `--lang ko` 또는 `--lang en`을 추가해 선택할 수 있습니다.

export는 전체 체인을 검증하고 새 디렉터리에만 기록하며, 복원 파일을 다시 검사합니다. Paper 26.1+에서 기록한 snapshot은 `world/dimensions/<namespace>/<dimension>` 구조로, 기존 snapshot은 종전의 월드별 단일 디렉터리 구조로 내보냅니다. 백업 저장소와 겹치는 경로와 심볼릭 링크를 거부하고 운영 월드를 자동 교체하지 않습니다.

Paper가 월드의 실제 저장 레이아웃을 바꾸면 다음 필터 없는 수동 또는 예약 백업을 새 전역 FULL 기준점으로 자동 승격합니다. 기존 체인과 metadata는 다시 쓰지 않습니다. 월드 필터가 있는 백업은 완전한 전역 기준점을 만들 수 없으므로 명확한 안내와 함께 거부합니다.

## 간단한 권한 역할

| 역할 | 허용 범위 |
|---|---|
| `timemachine.viewer` | 상태, doctor, 기록 조회 |
| `timemachine.operator` | viewer 범위, 백업, 검증 |
| `timemachine.admin` | 모든 TimeMachine 명령 |

OP는 별도 설정 없이 전체 권한을 받습니다. 세부 조정이 필요하면 기존 `timemachine.*` 개별 권한도 그대로 사용할 수 있습니다.

## 백업 범위와 한계

TimeMachine은 `region`, `entities`, `poi` 폴더의 `.mca` 파일을 보호합니다. 플레이어 데이터, `level.dat`, datapack, 플러그인 데이터, 설정과 기타 서버 파일은 별도의 검증된 원격 백업으로 보호해야 합니다.

Folia, hot reload, 온라인 복원, 압축, 암호화, 클라우드 업로드와 webhook 알림은 지원하지 않습니다.

## 문서

- [한국어 운영 설명서](docs/usage-ko.md)
- [아키텍처와 안전 경계](docs/architecture-ko.md)
- [SAFE/FAST 벤치마크](docs/change-detection-benchmark.ko.md)
- [소개 페이지 문안과 메타데이터](docs/project-page.ko.md)
- [후원·유료 운영 지원 계획](docs/sustainability-ko.md)
- [릴리스 절차](docs/releasing.md)
- [기여 안내](CONTRIBUTING.md) · [보안 정책](SECURITY.md) · [변경 기록](CHANGELOG.md)

## 빌드와 라이선스

`./gradlew clean build --console=plain`을 실행합니다. Windows에서는 `.\gradlew.bat`를 사용합니다. 배포 JAR은 `build/libs/Timemachine-<version>.jar`에 생성됩니다.

TimeMachine은 [MIT License](LICENSE)로 배포됩니다. 법적 권리자 표기는 LICENSE 원문을 따릅니다.

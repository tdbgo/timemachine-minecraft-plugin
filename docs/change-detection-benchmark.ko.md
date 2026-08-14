# SAFE/FAST 변경 감지 벤치마크

한국어 · [English](change-detection-benchmark.md)

이 문서는 `backup.change-detection` 선택에 필요한 참고 수치를 제공합니다. 특정 서버의 성능이나 전체 백업 시간을 보장하지 않습니다.

## 결과

2026-07-22 로컬 합성 데이터, warm filesystem cache, 중앙값 기준 결과입니다.

| 데이터 구성 | 총 용량 | FAST | SAFE | SAFE/FAST 시간비 |
|---|---:|---:|---:|---:|
| 512개 × 1 MiB | 512 MiB | 11.2 ms | 188.6 ms | 16.8× |
| 4,096개 × 64 KiB | 256 MiB | 87.2 ms | 378.3 ms | 4.3× |

측정 환경은 Windows, Eclipse Temurin 21.0.9, SHA-256 작업 스레드 2개입니다. 각 구성은 1회 warm-up 뒤 5회 실행한 중앙값입니다. SAFE는 플러그인의 `FileHashes.sha256` 구현으로 해시 전후 파일 속성을 확인했고, FAST는 크기와 수정 시각을 읽었습니다.

Java 21은 과거 microbenchmark에만 사용했습니다. 현재 플러그인은 Java 25가 필요합니다.

## 해석

- FAST는 주로 파일 개수와 filesystem metadata 응답 시간에 영향을 받습니다.
- SAFE는 추적 파일의 총용량과 저장 장치 읽기 성능에도 영향을 받습니다.
- 실제로 복사하는 파일은 두 모드 모두 해시하고 검증합니다.
- 월드 저장과 변경 파일 복사 시간이 길면 전체 백업의 모드 차이가 작아질 수 있습니다.
- cold cache, HDD, 네트워크 저장소와 운영 중인 서버에서는 결과가 달라질 수 있습니다.

4.3~16.8배는 이 로컬 측정에서 변경 없는 파일을 선별한 단계에만 적용됩니다.

## 선택 기준

해싱 단계가 실제 병목이 아니라면 `safe`를 유지하십시오. SAFE는 파일 크기와 수정 시각이 유지된 내용 변경도 감지합니다.

`fast`는 다음 조건을 모두 만족할 때만 검토하십시오.

- `/tmb status`에서 hashing 단계가 운영 병목으로 확인됨
- 저장소 또는 동기화 도구가 수정 시각을 신뢰성 있게 갱신함
- 같은 크기·같은 수정 시각의 변경을 놓칠 위험을 수용함
- 정기 FULL 백업과 오프라인 복원 검증을 별도로 수행함

```yaml
backup:
  change-detection: fast
```

변경 후 `/tmb reload`와 `/tmb doctor`로 적용 상태를 확인하십시오.

# NetPulse: 대규모 네트워크 패킷 로그 분석 및 모니터링 플랫폼 아키텍처

초당 수만 건씩 유입되는 네트워크/디바이스(CCTV/NVR) 품질 로그를 안정적으로 적재·집계하고, 대용량 데이터 조회 및 추출 시 발생하는 애플리케이션/인프라 병목을 해결한 아키텍처 고도화 프로젝트입니다.

---

## 1. 핵심 엔지니어링 과제 및 해결 과정 (Key Engineering Challenges)

### ① 인프라: 하이브리드 스토리지(SSD/HDD) 기반 ES Hot-Cold 아키텍처 및 ILM 구축
* **문제 상황:** 단일 SSD 스토리지에 모든 패킷 로그를 적재함에 따라 디스크 용량 포화 및 스토리지 증설 비용 급증. 최근 5일 이내 데이터에 조회가 집중되고 이후 데이터는 간헐적 이력 확인용으로만 사용되는 비대칭적 접근 패턴 확인.
* **해결 방안:**
  * 단일 클러스터 내 노드를 **Hot Tier(SSD, Heap 30GB)**와 **Cold Tier(HDD, Heap 16GB)**로 분리하고, **ILM(Index Lifecycle Management)** 정책을 통해 생성 5일 후 Cold 노드 자동 마이그레이션(`readonly` 전환) 및 30일 후 자동 삭제 구현.
  * 애플리케이션은 기존과 동일하게 Hot 노드(9200 포트)로만 질의하며, 클러스터 내부 라우팅을 통해 코드 변경 없이 스토리지 계층화 달성.
* **주요 트러블슈팅:**
  * **폐쇄망 `.geoip_databases` 샤드 미할당(Red) 해결:** Elasticsearch 8.x의 시스템 인덱스가 요구하는 `data_content` 롤을 Hot 노드에 추가 할당하고, 폐쇄망 특성을 고려해 외부 GeoIP 다운로더(`ingest.geoip.downloader.enabled: false`)를 비활성화하여 클러스터 상태 정상화.
  * **OS 커널 튜닝:** 30GB Heap 메모리 잠금(`bootstrap.memory_lock: true`) 시 발생하는 Bootstrap Check 실패를 해결하기 위해 Linux `memlock` 한도를 `unlimited`로 상향하고 `vm.max_map_count=262144` 적용.

### ② 백엔드 코어: NIO 클라이언트와 후처리 스레드 풀 분리(Bulkhead) 및 Deep Pagination
* **문제 상황:**
  1. 대시보드 진입 시 다수의 시계열 집계 쿼리가 동시 실행되며, 특정 슬로우 쿼리나 대량 JSON 응답 파싱 작업이 전체 서블릿 스레드 및 I/O 스레드를 지연시킴.
  2. Elasticsearch 기본 제약인 `max_result_window`(10,000건)를 초과하는 후반부 페이지 조회 불가.
* **해결 방안:**
  * **역할 분리 및 격벽(Bulkhead) 적용:** 네트워크 I/O는 `ElasticsearchAsyncClient` 내부 NIO 디스패처 스레드가 전담하고, 응답 도착 후 수행되는 1,440개 버킷 파싱 및 객체 변환(CPU-bound 작업)은 코어 수로 고정된 전용 스레드 풀(`esQueryExecutor`)의 `thenApplyAsync`로 위임.
  * **부분 실패 허용(`FutureUtil`):** 개별 비동기 작업에 `orTimeout(8, TimeUnit.SECONDS)`을 강제하고, 타임아웃이나 스레드 풀 포화(`RejectedExecutionException`) 발생 시 빈 컬렉션(`emptyList`)으로 즉시 Fallback 처리하여 전체 API 장애 전파 차단.
  * **PIT + `search_after` 하이브리드 커서 페이징:** `max_result_window`를 무리하게 늘려 Heap OOM을 유발하는 대신, **PIT(Point In Time)** 스냅샷과 `search_after` 스텝 점프(`advanceBy`)를 조합하여 10,000건 이후 구간에서도 정합성 있는 페이징 구현 및 완료 즉시(`whenCompleteAsync`) PIT 리소스 해제.

### ③ 성능 최적화 & Rust PoC: 대용량 엑셀 다운로드 병목 제거 및 마이크로서비스 오프로딩
* **문제 상황:**
  1. 기존 Java `ExcelUtil`(`SXSSFWorkbook`) 코드에서 `XSSFCellStyle`을 `static` 필드로 공유하여 동시 다운로드 시 스타일 참조 충돌(`IllegalArgumentException`) 발생 위험.
  2. 10,000행 × 20열(20만 회) 이중 루프 내부에서 매번 `String.replaceAll()`과 `matches()`를 호출하여 수십만 번의 정규식 재컴파일(`Pattern.compile`)로 인한 CPU 점유율 급증.
  3. 10만 건 이상의 대용량 로그 추출 시 단명 객체 대량 생성으로 인한 메인 API 서버의 JVM GC 부담 가중.
* **해결 방안:**
  * **1차 개선 (Java 리팩토링):** 워크북 생명주기에 귀속되는 불변 `StylePalette` 레코드를 도입해 완벽한 스레드 격리를 달성하고, 정규식 사전 컴파일(`static final Pattern`) 및 컬럼별 변환 규칙 사전 연산(`ColumnContext`)을 통해 루프 내부 연산을 $O(1)$ 배열 참조로 최적화.
  * **2차 고도화 (Rust Exporter 오프로딩):** 대량 추출 작업을 메인 JVM 서버에서 분리하기 위해 **Rust(`Axum` + `Tokio`) 기반 추출 워커** 프로토타입 구축.
    * `serde` 강타입 구조체 역직렬화와 ES `search_after` 배치 페칭(5,000건 단위) 결합.
    * `.xlsx` 생성 시 `tokio::task::spawn_blocking`으로 ZIP 압축 연산을 블로킹 스레드 풀에 격리하고, 초거대 로그는 `async-stream` 기반의 **Zero-Memory Chunked CSV 스트리밍**으로 처리하여 GC 멈춤 없이 수십 MB의 일정한 메모리로 처리 완료.

---

## 3. 기술 스택 및 라이브러리 출처 (Tech Stack & Libraries)

| 구분 | 기술 / 라이브러리 | 버전 | 용도 및 선정 사유 (출처) |
| :--- | :--- | :--- | :--- |
| **Backend (Main)** | Java / Spring Boot | 17 / 3.x | 레코드(`record`), 텍스트 블록 및 엔터프라이즈 웹 프레임워크 (VMware) |
| | `elasticsearch-java` | 8.x | 강타입 DSL 빌더 및 Apache HttpAsyncClient 기반 비동기 통신 (Elastic) |
| | Apache POI (`poi-ooxml`) | 5.x | `SXSSFWorkbook` 기반 슬라이딩 윈도우 엑셀 스트림 생성 (Apache) |
| **Exporter (PoC)** | Rust / `axum` / `tokio` | 1.75+ / 0.7 | 비가비지 컬렉션(Non-GC) 기반 고성능 비동기 런타임 및 웹 서버 (Tokio) |
| | `rust_xlsxwriter` | 0.79 | 순수 Rust 기반 고속 XLSX 바이너리 생성 (Crates.io / John McNamara) |
| | `async-stream` | 0.3 | HTTP Chunked CSV 실시간 스트리밍 응답 생성 (Tokio) |
| **Infra / Data** | Elasticsearch / Logstash | 7.16.0 | Hot-Cold 티어링, ILM 수명주기 관리 및 패킷 로그 수집 파이프라인 (Elastic) |

---

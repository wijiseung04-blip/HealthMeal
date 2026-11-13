# HealthMeal

공공 식품 데이터로 계산하는 맞춤형 레시피 영양 조절 서비스 (Spring Boot + Thymeleaf + MySQL).

## 실행

1. MySQL에 DB 생성: `CREATE DATABASE healthmeal DEFAULT CHARACTER SET utf8mb4;`
2. 접속 정보는 환경변수로 지정 (기본값: `localhost:3306/healthmeal`, `root`, 비밀번호 없음)
   - `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
3. `HealthMeal` 폴더에서 `./gradlew bootRun` → http://localhost:8080

테스트는 H2 인메모리 DB로 실행: `./gradlew test`

## 데이터 수집 (별도 프로그램)

공공 API 원본 응답을 `HealthMeal/data/raw/`에 그대로 저장한다. 키는 환경변수로만 받는다.

| 환경변수 | 발급처 | 비고 |
| --- | --- | --- |
| `FOODSAFETY_API_KEY` | 식품안전나라 (COOKRCP01) | |
| `DATA_GO_KR_API_KEY` | 공공데이터포털 (국가표준식품성분) | **Decoding 키** |

```bash
./gradlew importData --args="collect"          # 레시피 + 식품 전체
./gradlew importData --args="collect recipes"  # 레시피만
./gradlew importData --args="collect foods"    # 식품만
```

이미 받은 파일은 건너뛰므로 중간에 끊겨도 다시 실행하면 이어서 받는다.

## 재료 파싱과 통계

```bash
./gradlew importData --args="stats"
```

| 결과 파일 | 내용 |
| --- | --- |
| `data/parsed/recipe-ingredients.csv` | 레시피 재료 13,138개의 파싱 결과 (소제목, 재료명, 수량, 단위, g, 상태) |
| `data/report/ingredient-stats.md` | 상태별 비율, 단위 빈도, 환산 필요 재료×단위 순위, 재료명 순위 |
| `data/goldset/` | 30개 레시피 정답셋 (사람 확인 완료: `recipe-ingredients-gold.csv`) |

정답셋 대비 파싱 정확도: `./gradlew importData --args="goldset"` → `data/report/goldset-accuracy.md`

## 식품 매칭·단위 환산

```bash
./gradlew importData --args="match"
```

| 결과 파일 | 내용 |
| --- | --- |
| `data/mapping/ingredient-mappings.csv` | 재료명 1,696종 → 식품 코드, 등급(EXACT/MANUAL_CONFIRMED/SIMILAR/UNMATCHED), 후보 |
| `data/mapping/unit-conversions.csv` | 재료×단위 → 1단위당 g. 원문 병기("달걀 50g(1개)")의 중앙값 |
| `data/mapping/ingredient-mappings-manual.csv` | **사람이 고치는 파일.** 재료명·식품코드를 적고 `confirmed`에 `O`를 넣으면 MANUAL_CONFIRMED |
| `data/parsed/foods.csv` | 식품 2,765개 요약 (주요 영양소) |
| `data/report/matching-stats.md` | 등급 분포, 계산 가능 범위, 미매칭·추정 매칭 상위 목록 |

`ingredient-mappings-manual.csv`의 `Claude 제안` 행은 확인 전이라 SIMILAR로 취급된다. 맞으면 `confirmed`에 `O`, 틀리면 `food_code`를 고친 뒤 `O`.

## 식품 DB 적재

```bash
./gradlew importData --args="load-foods"
```

MySQL의 `foods`, `nutrition_definitions`, `food_nutrients`, `data_versions`에 넣는다(웹 서버는 띄우지 않음). 다시 실행하면 기존 식품 데이터를 지우고 새로 넣는다. 영양소 값의 `-`(미측정)은 NULL, `Tr`은 0(TRACE), 괄호 값은 추정값(ESTIMATED)으로 구분해 저장한다.

## 구조

| 패키지 | 계층 |
| --- | --- |
| `controller` | MVC 컨트롤러, 전역 예외 처리 |
| `service` | 비즈니스 로직 |
| `repository` | Spring Data JPA |
| `domain` | 엔티티 |
| `dto` | 폼 객체와 검증 |
| `config` | 보안 설정 |

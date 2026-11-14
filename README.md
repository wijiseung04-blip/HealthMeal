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
| `data/mapping/unit-conversions.csv` | 재료×단위 → 1단위당 g. 원문 병기("달걀 50g(1개)")의 중앙값. 부피 단위(ml·작은술 5ml·큰술 15ml·컵 200ml)는 재료별 g/ml 하나로 표본을 합침 |
| `data/mapping/unit-conversions-manual.csv` | **사람이 확인하는 개당 중량** (레몬 1개 등). `confirmed`가 `O`인 행만 쓰고, 원문 도출값이 있으면 그것이 우선 |
| `data/mapping/ingredient-mappings-manual.csv` | **사람이 고치는 파일.** 재료명·식품코드를 적고 `confirmed`에 `O`를 넣으면 MANUAL_CONFIRMED |
| `data/parsed/foods.csv` | 식품 2,765개 요약 (주요 영양소) |
| `data/report/matching-stats.md` | 등급 분포, 계산 가능 범위, 미매칭·추정 매칭 상위 목록 |

`ingredient-mappings-manual.csv`의 `Claude 제안` 행은 확인 전이라 SIMILAR로 취급된다. 맞으면 `confirmed`에 `O`, 틀리면 `food_code`를 고친 뒤 `O`.
`unit-conversions-manual.csv`는 숫자가 영양 합계에 바로 곱해지므로 확인(`O`) 전에는 쓰지 않는다.

매칭 규칙 (위에서부터 먼저 맞는 것):
1. 수동 지정 → 2. 물·육수(영양 0) → 3. 식품명 그대로 → 4. 동의어(쇠고기→소고기, 요거트→요구르트, 노른자→난황, 녹말→전분 등)·조리 상태·색 표기를 정리한 대표명·세부명
5. 대표명 + 세부 부위("돼지등심" → 돼지고기, 등심 / "닭다리살" → 닭고기, 다리) — 나머지가 세부명과 정확히 같을 때만 → 6. 가루 표기 → 7. 끝말("칵테일새우" → 새우)
- 젓갈·튀김·과자 형태밖에 없으면 재료명에 그 표기가 없는 한 매칭하지 않는다(낙지 ≠ 낙지 양념젓갈).
- 같은 식품으로 매칭된 재료명끼리 밀도를 나눠 쓴다("저염 간장 2큰술" → 간장의 g/ml). 개·장 등 개수 단위는 크기마다 달라 나누지 않는다.

커버리지 변화 (모든 재료가 계산되는 레시피): 23.3% → 34.0%, 개당 중량 15건을 확인하면 37.0%.
남은 큰 원인: "소금·후추 약간"(사용량 표기 없음, 일부러 미확인으로 둠), 국가표준식품 API에 없는 식품(다시마·바지락·주꾸미·홍합·생 전복).

## 식품 DB 적재

```bash
./gradlew importData --args="load-foods"
```

MySQL의 `foods`, `nutrition_definitions`, `food_nutrients`, `data_versions`에 넣는다(웹 서버는 띄우지 않음). 다시 실행하면 기존 식품 데이터를 지우고 새로 넣는다. 영양소 값의 `-`(미측정)은 NULL, `Tr`은 0(TRACE), 괄호 값은 추정값(ESTIMATED)으로 구분해 저장한다.

## 레시피 DB 적재와 영양 계산

```bash
./gradlew importData --args="load-recipes"     # load-foods 다음에 실행
./gradlew importData --args="nutrition-check"  # DB 없이 전체 레시피 계산 커버리지 확인
```

- `load-recipes`: `recipes`, `recipe_steps`, `recipe_ingredients`(원문·파싱·확정 중량·매칭 식품)에 적재
- 영양 계산 엔진 `nutrition/NutritionEngine`: 재료 영양소량 = 100g당 값 × 사용량(g) / 100
  - 미측정 값은 0으로 메우지 않고 영양소별 커버리지(COMPLETE / PARTIAL / NONE)로 표시
  - 사용량·식품을 모르는 재료는 미확인 목록으로 분리, 이때 합계는 하한값
  - 대체 전후 비교: 미확인 재료가 있으면 변화율은 상한값(UPPER_BOUND), 대체 전 합계 0이면 변화량만
- API: `GET /api/recipes/{id}/nutrition` (비회원 조회 가능)
- 화면: `/recipes` 검색(이름·음식 종류, 12개씩), `/recipes/{id}` 상세(영양 합계·계산 범위, 재료별 중량·연결 식품·에너지·나트륨, 조리 순서, 저염 팁)
- 결과 파일: `data/parsed/recipe-nutrition.csv`, `data/report/nutrition-coverage.md`

## 식재료 감량·대체

레시피 상세에서 영양 목표(저나트륨·저칼로리·고단백)를 고르면 `/recipes/{id}/adjust`에서 후보와 전후 비교를 본다. API: `GET /api/recipes/{id}/substitutions?goal=LOW_SODIUM`

| 종류 | 대상 | 추천 조건 |
| --- | --- | --- |
| 감량 20·30·50% | 조미료류(간장·소금·된장 등) | 자동 승인 |
| 감량 | 유지류·당류 | 검수 대기 (식감·구조 영향) |
| 대체 | `data/mapping/substitute-pool.csv`의 규칙 | `confirmed`가 `O`인 규칙만. 기본 1:1 중량, 허용 비율 범위 안 |

- 모든 후보를 계산 엔진으로 다시 계산해, 목표 영양소를 실제로 목표 방향으로 바꾸는 것만 남긴다.
- 판정: 모든 재료 확인 + 10% 이상 → 목표 달성 / 10% 미만(상한값이 10% 미만 포함) → 미달 / 미확인 재료로 상한값만 있음 → 판정 보류
- 대체 규칙을 확인했으면 `confirmed`에 `O`를 넣고 `load-recipes`를 다시 실행한다.

### 맞춤 레시피 저장 (회원)

- 비교 화면의 "내 맞춤 레시피로 저장"(`POST /recipes/{id}/adjust/save`) → `user_substitutions` 테이블에 저장, 마이페이지에서 목록·다시 보기·CSV·삭제
- 저장 시점의 전후 값·판정을 함께 남긴다. 같은 레시피·목표·후보를 다시 저장하면 새 줄 대신 저장 시각만 갱신
- 레시피는 원본 번호(`RCP_SEQ`)로 기억해, `load-recipes`를 다시 실행해 id가 바뀌어도 이력이 레시피를 찾아간다
- 삭제는 본인 것만 가능

## 파일 업로드·다운로드

| 기능 | 주소 | 접근 |
| --- | --- | --- |
| 레시피 CSV 다운로드 | `GET /recipes/{id}/download` | 누구나 |
| 맞춤 레시피 CSV 다운로드 (감량·대체 적용, 전후 비교 포함) | `GET /recipes/{id}/download?goal=..&option=..` | 누구나 |
| 건강검진 PDF 업로드 | `POST /mypage/checkups` | 회원 |
| 내 검진 파일 다운로드·삭제 | `GET /mypage/checkups/{id}/file`, `POST /mypage/checkups/{id}/delete` | 본인만 (다른 회원 파일은 404) |

- 업로드 검증: `.pdf` 확장자 + 파일 내용의 PDF 서명(`%PDF-`), 10MB 이하
- 저장: 공개 폴더 밖 `uploads/`(환경변수 `UPLOAD_DIR`로 변경)에 임의 이름(UUID)으로 저장, 원래 이름은 DB에만 보관
- CSV는 UTF-8(BOM)이라 엑셀에서 한글이 깨지지 않는다

## 건강검진 수치 추출과 영양 목표 추천

마이페이지에서 올린 검진 PDF의 **"수치 추출"** → 추출값 확인·수정 → 저장 → 항목별 판정과 추천 목표. PDF 없이 직접 입력도 가능.

- 추출 (`checkup/CheckupExtractor`): PDFBox로 글자를 읽고, 항목 동의어(`TG`·`중성지방`·`트리글리세라이드` 등)를 찾아 **이름 바로 뒤의 첫 숫자**를 결과값으로 쓴다(같은 줄의 참고치는 쓰지 않음). 혈압은 `138/88`, 검진일도 읽는다. 사람 수치로 불가능한 값은 버린다. AI 사용 안 함
- 글자가 없는 PDF(스캔본)는 직접 입력으로 안내 (OCR 미지원)
- 저장: 사용자가 확인한 값만 저장, 값의 출처(PDF 추출 / 추출 후 수정 / 직접 입력)를 함께 기록
- **암호화**: 검진 수치는 AES-256-GCM으로 암호화해 저장. 키는 환경변수 `HEALTH_DATA_KEY`(32바이트 Base64). `application.properties`의 기본 키는 로컬 개발용
- 판정·추천: `src/main/resources/checkup-rules.csv` (국민건강보험공단 일반건강검진 판정 기준 등 요약, **원문 대조 후 사용**). 질환 의심 범위면 의료기관 상담 안내를 먼저, 신장 수치 이상이면 단백질 증가 추천 안 함. 추천 목표는 `/recipes?goal=...`로 레시피 화면까지 이어진다
- 단독 실행 추출기 (웹과 별도 프로그램):

```bash
./gradlew extractCheckup --args="검진결과.pdf"
```

## 구조

| 패키지 | 계층 |
| --- | --- |
| `controller` | MVC 컨트롤러, 전역 예외 처리 |
| `service` | 비즈니스 로직 |
| `nutrition` | 공통 영양 계산 엔진 (DB 무관 순수 계산) |
| `checkup` | 검진 PDF 수치 추출기·판정 규칙 (DB 무관) |
| `pipeline` | 데이터 구성 프로그램 (`importData`) |
| `repository` | Spring Data JPA |
| `domain` | 엔티티 |
| `dto` | 폼 객체와 검증 |
| `config` | 보안 설정 |

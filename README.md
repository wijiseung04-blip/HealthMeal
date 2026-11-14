# HealthMeal

공공 식품 데이터로 계산하는 맞춤형 레시피 영양 조절 서비스 (Spring Boot + Thymeleaf + MySQL).

## 실행

1. MySQL에 DB 생성: `CREATE DATABASE healthmeal DEFAULT CHARACTER SET utf8mb4;`
2. 접속 정보는 환경변수로 지정 (기본값: `localhost:3306/healthmeal`, `root`, 비밀번호 없음)
   - `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
3. `HealthMeal` 폴더에서 `./gradlew bootRun` → http://localhost:8080

테스트는 H2 인메모리 DB로 실행: `./gradlew test`

## 회원

- 아이디(영문 소문자·숫자·`_` 4~20자) + 비밀번호(8~64자)로 가입·로그인. 이메일은 받지 않는다
- 이메일 방식에서 바꾼 기존 DB는 회원 데이터를 비우고 `email` 열을 지운 뒤 앱을 시작한다 (아래 SQL)

```sql
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE checkup_values; TRUNCATE health_checkups; TRUNCATE checkup_files; TRUNCATE user_substitutions; TRUNCATE users;
SET FOREIGN_KEY_CHECKS = 1;
ALTER TABLE users DROP COLUMN email;
```

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

### 보충 식품: 수산물 표준데이터 (파일)

국가표준식품성분 API에 없는 수산물(다시마·바지락·주꾸미·홍합·생 전복 등)을 보충한다.

- 원본: [전국통합식품영양성분정보(원재료성식품) 표준데이터](https://www.data.go.kr/data/15100065/standard.do) CSV를 받은 그대로 `data/raw/food-integrated/raw-foods.csv`에 둔다 (CP949, 키 불필요)
- 3,704행 중 **국립수산과학원(표준수산물성분표)·식약처 수산물 1,826행만** 쓴다. 농진청 행은 API와 겹쳐 뺀다
- 지역·월별 표본은 식품별 대표값 하나로 (대표·평균 > 대표 > 평균) → 1,055개
- 식품명은 "참다시마(다시마), 말린것" 형식. 종 이름이 대표명으로 끝나면 대표명을 별칭으로 붙여 레시피의 "다시마"가 찾는다
- 영양소 열은 API 이름으로 맞춘다(포화지방산 → 총 포화 지방산 등). 빈 값은 0이 아니라 미측정, `수집` 값은 추정값으로 저장
- 매칭은 **국가표준에서 후보를 못 찾았을 때만** 보충 식품을 쓴다(기존 매칭은 그대로). 상세 화면에 "(수산물 표준데이터)"로 출처 표시
- `load-foods`가 두 출처를 함께 적재하고 `data_versions`에 출처별로 남긴다

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
- 건·말린·마른 재료와 가루는 말린 식품(또는 가루)으로만 매칭한다. 없으면 생것으로 대신하지 않는다("건포도"처럼 그 자체가 식품명이면 그대로).
- 젓갈·튀김·과자 형태밖에 없으면 재료명에 그 표기가 없는 한 매칭하지 않는다(낙지 ≠ 낙지 양념젓갈).
- 같은 식품으로 매칭된 재료명끼리 밀도를 나눠 쓴다("저염 간장 2큰술" → 간장의 g/ml). 개·장 등 개수 단위는 크기마다 달라 나누지 않는다.

커버리지 변화 (모든 재료가 계산되는 레시피): 23.3% → 34.0%(매칭·환산 규칙) → 39.3%(수산물 보충) → 42.6%(AI 교차검토·사람 결정 반영).
남은 큰 원인: "소금·후추 약간"(사용량 표기 없음, 일부러 미확인으로 둠), 개당 중량 미확인(레몬·대추 등, `unit-conversions-manual.csv` 확인 대기).

## AI 교차검토 (Gemini 무료 등급)

Claude가 만든 확인 대기 제안(식품 연결·대체 규칙·개당 중량)을 Gemini에게 **독립적으로** 물어 비교한다.

```bash
./gradlew importData --args="ai-review"   # 환경변수 GEMINI_API_KEY 필요 (선택: GEMINI_MODEL, 기본 gemini-4-hydrogen)
```

| 항목 | Gemini에게 묻는 방식 | 일치 기준 |
| --- | --- | --- |
| 식품 연결 | 제안 식품과 비슷한 후보 최대 12개를 코드순으로 보여 주고 고르게 함 (제안이 무엇인지 알려 주지 않음, 없으면 NONE) | 같은 식품코드 |
| 대체 규칙 | 가정 요리에서 쓸 만한 대체인지 | ok = true |
| 개당 중량 | 우리 숫자를 지운 설명만 주고 g을 직접 추정 | ±20% 이내 |

- 일치하면 해당 CSV의 `confirmed`를 `AI`로 바꿔 사용한다. 사람 확인 `O`는 건드리지 않는다
- 사람이 보류한 대체 규칙은 `confirmed`에 `X` → 추천·검수 대기 목록·AI 재검토에서 모두 빠진다
- 첫 실행 결과: 105건 중 96건 일치, 불일치 9건은 사람이 결정(식품 5건 Claude 제안 유지, 대체 규칙 2건 보류, 대파 100g·대하 35g)
- 화면 표시: AI로 정한 개당 중량은 "(AI 추정 중량)", AI로 통과한 대체 규칙은 주의 문구에 "AI 교차검토 규칙(사람 확인 전)"
- 불일치 목록: `data/report/ai-review.md` (사람이 확인할 것), 전체 결과 `data/review/ai-review.csv`
- 응답은 `data/review/raw/`에 저장해 다시 실행해도 같은 질문은 API를 부르지 않는다
- 보내는 데이터는 공개 데이터(식품명·재료명)뿐이다. 회원·검진 데이터는 보내지 않는다
- 역할 판단 표본(`ingredient-roles-check.csv`)은 정확도 측정용 정답지라 AI로 채우지 않는다

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
| 감량 20·30% | 유지류·당류 중 조리 순서로 볶음·부침용 기름, 맛 내기(양념·소스·드레싱)로 판단된 것 | 판단 근거(조리 순서 문장)를 함께 표시 |
| 감량 안 함 | 유지류·당류 중 튀김 기름, 반죽·머랭 등 구조 | 줄이면 조리가 달라져 안내만 |
| 검수 대기 | 유지류·당류 중 조리 순서에서 역할을 찾지 못한 것 | |
| 대체 | `data/mapping/substitute-pool.csv`의 규칙 | `confirmed`가 `O`인 규칙만. 기본 1:1 중량, 허용 비율 범위 안 |

- 모든 후보를 계산 엔진으로 다시 계산해, 목표 영양소를 실제로 목표 방향으로 바꾸는 것만 남긴다.
- 판정: 모든 재료 확인 + 10% 이상 → 목표 달성 / 10% 미만(상한값이 10% 미만 포함) → 미달 / 미확인 재료로 상한값만 있음 → 판정 보류
- 대체 규칙을 확인했으면 `confirmed`에 `O`를 넣고 `load-recipes`를 다시 실행한다.

### 기능적 식재료 대체 (조리 역할 + 영양성분)

- **재료별 조리 역할**: 식품군과 조리 순서로 정한다 — 주 단백질, 탄수화물 베이스, 결착·농도(소량 전분), 유제품, 채소·과일, 양념, 고소한 맛·토핑, 기름·설탕은 아래 규칙. 조절 화면의 "재료별 조리 역할 보기"
- **같은 식품의 다른 부위·형태** (자동, 식품 DB에서): 식품명 구간 수가 같고 딱 한 구간만 다르며 조리 상태가 같은 것
  - 고기는 부위·등급(삼겹살→안심, 한우 1++→3등급), 곡류는 더 거친 곡물(백미→현미·칠분도미), 유제품은 저지방·저당만
  - 내장·가공 형태 제외, 목표 영양소 합계를 1% 이상 바꿀 때만, 재료마다 2개까지
- **묶음 제안**: 하나로 목표(10%)에 못 미치면 재료마다 가장 좋은 후보를 개선 폭 순으로 2~3개 더해 목표에 닿게. 다른 영양소를 나쁘게 하는 후보는 넣지 않는다
- **맛·식감 영향**: 감량 20% 낮음 / 30% 보통 / 50% 큼, 부위·곡물 바꾸기 보통(유제품 낮음), 주의 문구가 있는 대체 규칙 보통, 묶음은 구성 중 가장 큰 것
- **전후 비교**: 주요 영양소 9종의 변화와 식약처 1일 영양성분 기준치 대비(전→후), 목표가 아닌 영양소가 10% 넘게 나빠지면(당류·나트륨·지방 증가, 단백질·식이섬유 감소) 경고

### 기름·설탕류 역할 판단 (규칙, AI 미사용)

`nutrition/IngredientRoleClassifier`: 조리 순서에서 재료가 나오는 문장을 찾아 단서 단어로 역할을 정한다.

1. 줄이면 안 되는 쪽 먼저: 이름에 "튀김", 또는 재료가 나온 절에 튀김·반죽·머랭·오븐·박력분·이스트 등 → 튀김 기름 / 구조
   (단, 재료 바로 뒤가 "두르고·볶아"면 팬 기름이므로 같은 문장의 반죽은 무시: "기름을 두르고 반죽을 부어")
2. 재료 바로 뒤 → 재료부터 문장 끝 → 문장 전체 순으로, 가장 먼저 나오는 단서: 두르·볶·부치·굽 → 볶음·부침용 / 양념·소스·무치·졸이·뿌리 → 맛 내기
3. 설탕류는 볶음·튀김 문장에 있어도 맛 내기 (튀김 소스, 볶다가 설탕)
4. 단서가 없거나 재료가 조리 순서에 나오지 않으면 판단하지 않음 (검수 대기)

```bash
./gradlew importData --args="roles"   # data/report/ingredient-roles.md, 확인용 표본 data/goldset/ingredient-roles-check.csv
```

- 유지류·당류 1,278개 중 76.4% 판단, 67.0%가 감량 후보
- 표본 100건의 `correct` 열에 맞으면 O, 틀리면 X를 적고 다시 실행하면 정확도와 틀린 사례를 리포트에 쓴다

### 맞춤 레시피 저장 (회원)

- 비교 화면의 "내 맞춤 레시피로 저장"(`POST /recipes/{id}/adjust/save`) → `user_substitutions` 테이블에 저장, 마이페이지에서 목록·다시 보기·CSV·삭제
- 저장 시점의 전후 값·판정을 함께 남긴다. 같은 레시피·목표·후보를 다시 저장하면 새 줄 대신 저장 시각만 갱신
- 레시피는 원본 번호(`RCP_SEQ`)로 기억해, `load-recipes`를 다시 실행해 id가 바뀌어도 이력이 레시피를 찾아간다
- 삭제는 본인 것만 가능

## 맞춤형 식사 조합 추천

`/meals` (비회원 가능). 상황(편의점·간편식 / 외식 / 직접 조리)과 영양 목표를 고르면 한 끼 기준 안에 들어오는 조합을 보여 준다. 회원은 최근 건강검진의 성별·추천 목표가 기본값.

```bash
./gradlew importData --args="load-meals"   # data/raw/meals/의 표준데이터 CSV + 공공 레시피 → meal_items
```

| 상황 | 데이터 | 한 끼 구성 |
| --- | --- | --- |
| 편의점·간편식 | [가공식품 표준데이터](https://www.data.go.kr/data/15100066/standard.do)의 즉석식품(김밥·도시락·샌드위치·버거·죽 등)·샐러드·달걀·음료(ml), 1회분 포장 중량 | 주식 (+곁들임) (+음료) |
| 외식 (메뉴 추천) | [음식 표준데이터](https://www.data.go.kr/catalog/15100070/standard.json)의 외식(분석·재료량 기반) 메뉴, 1인분 중량 | 한 그릇 메뉴 / 국·찌개 + 공기밥 (기본 반찬은 계산 제외) |
| 직접 조리 | 공공 레시피의 식약처 1인분 영양정보(검증 통과분), 레시피 상세로 연결 | 한 그릇 (+반찬) / 밥 + 국 + 반찬 / 밥 + 반찬 2 |

- 한 끼 기준(하루의 1/3): 열량 = 성별 하루 열량(남 2,500 / 여 2,000 / 미선택 2,200kcal)의 80~120%, 나트륨 ≤ 767mg(하루 2,300mg), 단백질 ≥ 15g, 당류 ≤ 25g(정보 있을 때)
  - 저나트륨: 나트륨 ≤ 600mg / 저칼로리: 열량 60~90% / 고단백: 단백질 ≥ 25g
- 순위: 목표 영양소(나트륨↓·열량↓·단백질↑), 목표가 없으면 한 끼 열량에 가깝고 나트륨이 적은 순. 같은 주식은 한 번만
- 회원은 "내 식사로 저장" → 마이페이지 "저장한 식사 조합"(`saved_meals`). 저장 시점의 품목·영양값을 남겨 후보를 다시 적재해도 유지, 같은 조합은 하나만
- 밥은 쌀밥 1공기 210g(식약처 1회 제공량)
- 데이터 정리:
  - 주식은 열량 200kcal·나트륨 100mg 이상만(밥·잡곡밥만인 상품 제외), 밀키트·분말 제외, 음료는 액체·250kcal 이하
  - 레시피 1인분 정보는 원본 오류가 있어 탄단지 환산 열량과 35% 넘게 어긋나거나, 나트륨 20mg 미만이거나, 계산 엔진 값과 4배 넘게 다르면 제외
- 한계: 가공식품 파일은 포털 다운로드 상한(5만 행)으로 일부만 들어 있고, 실제 편의점 판매 여부는 데이터에 없다

## 개인 레시피 (회원)

마이페이지 → "새 레시피 등록"(`/myrecipes/new`). 재료·조리 순서를 한 줄에 하나씩 적는다.

- **재료 분석해 보기**: 저장 전에 재료별 중량(g)·연결 식품·계산 안 되는 재료를 미리 본다
- 저장하면 공공 레시피와 같은 규칙(파서 → 환산표·확인된 개당 중량 → 식품 매칭)으로 분석해 `recipes`(owner_id) / `recipe_ingredients` / `recipe_steps`에 넣는다
  → 상세·영양 계산·감량/대체·맞춤 레시피 저장·CSV를 그대로 쓴다
- **본인만** 볼 수 있다: 다른 회원·비회원은 상세·조절·CSV·API 모두 404, 레시피 검색·음식 종류 목록에서도 빠진다
- 수정·삭제는 주인만. `load-recipes`는 공공 레시피만 바꾸고, 개인 레시피는 최신 매칭 규칙으로 다시 분석한다
- 웹 분석은 `data/mapping/unit-conversions.csv`(match 단계 결과)와 수동 매핑 CSV를 읽는다 (`IngredientAnalyzer`)

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

# 코드 파일 요약

`HealthMeal/` 아래 모든 코드 파일을 한 줄씩 설명한다. 패키지 경로는 `src/main/java/com/springboot/` 기준이다.
전체 연결 구조(그림)는 [README의 코드 연결 구조](../README.md#코드-연결-구조), 수업 자료(3~14장)와의 대응은 [course-alignment.md](course-alignment.md)를 본다.

- 메인 Java 111개 · 테스트 29개(Java 28 + JS 1) · 리소스(설정·메시지·템플릿·정적 파일) 32개
- 장 표시(8장, 9장 …)는 수업 자료 해당 장의 방식으로 만든 파일이다.

## 1. 진입점

| 파일 | 설명 |
|---|---|
| `HealthMealApplication.java` | 스프링 부트 웹 앱 시작점 (`./gradlew bootRun`) |

## 2. config — 설정

| 파일 | 설명 |
|---|---|
| `SecurityConfig.java` | 스프링 시큐리티: 비회원 허용 경로(홈·가입·로그인·레시피 조회·식사 조합·영양 퀴즈), 폼 로그인/로그아웃, CSRF. 데이터 적재 프로그램(웹 없이 실행)에서는 꺼진다 |
| `PasswordConfig.java` | 비밀번호 암호화기(BCrypt) 빈 |
| `HealthDataCipher.java` | 검진 수치(민감정보) 컬럼 암호화. AES-256-GCM, 값마다 임의 IV, 키는 환경변수 `HEALTH_DATA_KEY` |
| `MessageConfig.java` | 8장 다국어: 세션 로케일(기본 한국어), `?lang=ko` / `?lang=en` 으로 변경 |
| `ValidationConfig.java` | 9장: 제약 애너테이션의 `message="{키}"`를 현재 로케일 메시지 리소스에서 가져온다 |
| `LoggingConfig.java` | 12장: `MonitoringInterceptor`를 정적 파일 외 모든 요청에 등록 |

## 3. controller — 웹 요청 처리

| 파일 | 설명 |
|---|---|
| `HomeController.java` | 홈 화면(`/`) |
| `MemberController.java` | 회원가입·로그인 화면. `@InitBinder`로 받을 필드 제한, `PasswordConfirmValidator` 등록 |
| `RecipeController.java` | 레시피 검색·상세·감량/대체 조정·맞춤 레시피 저장·CSV·즐겨찾기 토글. 없는 레시피는 `@ExceptionHandler`로 error/recipe |
| `RecipeApiController.java` | 레시피 영양 계산 결과 JSON (비회원도 조회) |
| `FavoriteApiController.java` | 13장 REST: 즐겨찾기 GET/PUT/DELETE `/api/favorites` (회원, JSON) |
| `MealController.java` | 맞춤형 식사 조합 추천과 저장. 회원은 최근 검진의 성별·추천 목표가 기본값 |
| `MyPageController.java` | 마이페이지: 프로필·저장 레시피·즐겨찾기·저장 식사·검진 파일 업로드/다운로드/삭제 |
| `HealthController.java` | 검진 수치 확인·저장·결과, 사진 AI 판독 요청·대기 화면·상태 조회(`/ai`, `/ai/wait`, `/ai/status`) |
| `GoalController.java` | 프로필 입력과 개인 영양 목표 보기·수정·다시 만들기 |
| `MyRecipeController.java` | 개인 레시피 등록·수정·삭제, 저장 전 "재료 분석" 미리보기 |
| `GlobalModelAttributes.java` | 모든 화면 헤더에 쓰는 공통 모델 속성(로그인 아이디) |

## 4. service — 업무 로직

| 파일 | 설명 |
|---|---|
| `MemberService.java` | 회원가입(비밀번호 암호화, 중복 아이디 거부) |
| `MemberUserDetailsService.java` | 로그인 시 회원 정보를 스프링 시큐리티에 제공 |
| `RecipeService.java` | 레시피 목록(검색·페이지)·상세 조회 |
| `RecipeNutritionService.java` | DB의 재료·식품 영양성분으로 `NutritionEngine` 실행 |
| `SubstitutionService.java` | 재료의 조리 역할·영양성분을 보고 감량·대체 후보를 만들고 계산 엔진으로 검증 (AI 없음) |
| `SavedRecipeService.java` | 감량·대체를 적용한 맞춤 레시피 저장·목록·삭제 |
| `RecipeExportService.java` | 레시피/맞춤 레시피를 엑셀용 CSV(UTF-8 BOM)로 |
| `FavoriteService.java` | 레시피 즐겨찾기 추가·삭제·토글·목록 (공공 레시피와 본인 개인 레시피만) |
| `MyRecipeService.java` | 회원 개인 레시피(같은 테이블에 owner 표시). 상세·계산·대체·CSV를 그대로 공유 |
| `MealPlanService.java` | 상황(편의점·외식·직접 조리)과 영양 목표로 한 끼 조합 추천. 영어 제외어를 한국어 이름으로 확장 |
| `MealPlanner.java` | 한 끼 목표(열량·나트륨·단백질·당·포화지방·식이섬유) 안의 조합을 찾는 순수 계산. 후보를 추린 뒤 조합 평가 |
| `SavedMealService.java` | 마음에 드는 식사 조합을 마이페이지에 저장 |
| `PersonalGoalService.java` | 프로필 → 하루 기준(KDRI), 검진 → 목표 항목. 회원이 고친 항목은 다시 만들 때 유지 |
| `HealthCheckupService.java` | 검진 PDF 규칙 추출 / 사진 AI 판독(백그라운드, 최대 5분) → 사용자 확인 → 암호화 저장 → 판정·목표 추천 |
| `CheckupFileService.java` | 검진 파일(PDF·JPEG·PNG·WEBP) 업로드·다운로드·삭제. 파일 시그니처 확인, 공개 폴더 밖 임의 이름 저장 |

## 5. checkup — 건강검진 해석

| 파일 | 설명 |
|---|---|
| `TestItem.java` | 표준 검사 항목(혈압·혈당·HbA1c·콜레스테롤 등). 동의어로 표기를 맞추고 가능한 값 범위로 오인식을 거른다 |
| `CheckupExtractor.java` | 검진 PDF 글자에서 수치를 규칙으로 추출. 값이 다음 줄에 있어도(워터마크로 갈린 줄) 최대 3줄까지 찾는다 |
| `CheckupExtractorCli.java` | 웹 없이 실행하는 추출 도구 (`./gradlew extractCheckup --args="파일.pdf"`) |
| `CheckupRules.java` | 수치 → 판정(정상·관리 필요·상담 권고)과 영양 목표 추천. 기준값은 `checkup-rules.csv` |
| `CheckupVision.java` | 결과표 사진을 AI로 읽는 인터페이스 (회원 동의 시에만) |
| `GeminiCheckupVision.java` | Gemini 사진 판독 구현. 이미지 축소, 추측 금지 지시, 범위 재검사, 혼잡·한도 재시도 |
| `VisionException.java` | AI 판독 실패 이유와 다시 시도 가능 시각(화면 안내용) |

## 5-1. game — 영양 퀴즈 게임 (브라우저에서 실행)

| 파일 | 설명 |
|---|---|
| `game/GameService.java` | 문제 재료: 식사 조합 후보에서 영양소 값이 있는 품목을 무작위 300개(같은 이름 제외, 영어 화면은 번역 있는 이름만). 영양소별 단위·1일 기준치·이어 볼 목표 |
| `game/GameController.java` | `/game` 화면과 `GET /api/game/foods?nutrient=` (잘못된 영양소는 400) |

## 6. goal · nutrition — 순수 계산

| 파일 | 설명 |
|---|---|
| `goal/GoalRules.java` | 개인 하루 기준(에너지필요추정량)과 검진 판정 → 영양 목표 항목 |
| `nutrition/NutritionEngine.java` | 공통 영양 계산 엔진. 100g당 값 × 사용량. 값이 없으면 0으로 메우지 않고 커버리지로 표시 |
| `nutrition/IngredientAnalysis.java` | 재료 한 줄의 조리 역할·영양 역할 2축 (규칙) |
| `nutrition/IngredientRoleClassifier.java` | 기름·설탕이 볶음 기름/맛 내기/구조 중 무엇인지 조리 순서 문장으로 판단 |

## 7. i18n · interceptor · validator · exception — 웹 공통

| 파일 | 설명 |
|---|---|
| `i18n/Translator.java` | 8장 확장(`@tr`): 서버가 만든 문장은 `sentences_en.txt` 틀로, 데이터 이름은 `data_en.csv`·`translations-en.csv` 사전으로 영어 변환 |
| `interceptor/MonitoringInterceptor.java` | 12장: 요청 URL·시작/종료 시각·소요 시간을 콘솔과 `logs/monitor.log`에 (요청 본문은 기록 안 함) |
| `validator/UniqueUsername.java` | 9장 사용자 정의 제약 애너테이션 (이미 있는 아이디면 오류) |
| `validator/UniqueUsernameValidator.java` | 9장 `ConstraintValidator`: 저장소에서 같은 아이디 확인 |
| `validator/PasswordConfirmValidator.java` | 9장 `Validator`: 비밀번호와 확인 값 일치 검사 |
| `exception/CommonException.java` | 11장 `@ControllerAdvice`: 처리되지 않은 예외 → error/common |
| `exception/RecipeNotFoundException.java` | 11장 `@ResponseStatus(404)`: 없거나 볼 수 없는 레시피 |
| `exception/ResourceNotFoundException.java` | 11장 404: 본인 것이 아니거나 없는 파일·검진 결과 |
| `exception/InvalidUploadException.java` | 허용하지 않는 업로드(형식·크기) |
| `exception/DuplicateUsernameException.java` | 이미 가입된 아이디 |

## 8. domain — JPA 엔티티 (DB 테이블)

| 파일 | 설명 |
|---|---|
| `Member.java` / `Role.java` | 회원(아이디·암호화 비밀번호)과 권한 |
| `MemberProfile.java` | 나이·성별·키·몸무게·활동량 (열량 기준 계산용) |
| `Food.java` / `FoodNutrient.java` / `NutritionDefinition.java` | 국가표준식품, 100g당 영양성분(세로형), 영양소 코드·이름·단위 |
| `Recipe.java` / `RecipeIngredient.java` / `RecipeStep.java` | 레시피(공공 또는 개인), 재료 줄(원문·파싱·중량·매칭 식품), 조리 순서 |
| `IngredientAnalysisRecord.java` | 재료별 역할 분석 결과 (`ingredient_analysis`) |
| `SubstitutePool.java` | 식품별 대체 후보 목록 (`substitute_pool`) |
| `SavedRecipe.java` | 회원이 저장한 맞춤 레시피 |
| `RecipeFavorite.java` | 레시피 즐겨찾기 (`recipe_favorites`) |
| `MealItem.java` | 식사 조합 후보(편의점·외식·직접 조리) 1회 제공량 영양 |
| `SavedMeal.java` | 회원이 저장한 식사 조합 |
| `NutritionGoal.java` | 영양 목표 프리셋 (저나트륨·저열량·저당·저포화지방·고식이섬유·고단백) |
| `NutritionGoalItem.java` | 회원별 영양소 목표 한 줄 (직접 고침 여부 포함) |
| `HealthCheckup.java` / `CheckupValue.java` | 확인된 검진 결과 한 건과 항목 값(암호문) |
| `CheckupFile.java` | 올린 검진 파일 정보 |
| `DataVersion.java` | 외부 데이터 적재 이력 |

## 9. dto · repository

| 파일 | 설명 |
|---|---|
| `dto/SignupForm.java` | 가입 폼 (아이디 형식·중복, 비밀번호 확인) |
| `dto/ProfileForm.java` | 프로필 입력 |
| `dto/CheckupForm.java` | 검진 수치 확인·입력 폼 |
| `dto/MyRecipeForm.java` | 개인 레시피 폼 (재료·조리 순서 한 줄에 하나) |
| `repository/MemberRepository.java` | 회원 조회 (아이디) |
| `repository/RecipeRepository.java` | 레시피 검색·상세 조회 |
| `repository/SavedRecipeRepository.java` | 맞춤 레시피 조회 |
| `repository/HealthCheckupRepository.java` | 회원별 검진 결과 조회 |
| `repository/CheckupFileRepository.java` | 회원별 검진 파일 조회 |

나머지 테이블은 서비스에서 `JdbcTemplate`/`EntityManager`로 직접 다룬다.

## 10. pipeline — 데이터 구성 프로그램 (`./gradlew importData --args="단계"`)

| 파일 | 설명 |
|---|---|
| `ImportData.java` | 진입점. collect·stats·match·goldset·nutrition-check·roles·ai-review·ai-translate·load-foods·load-recipes·load-meals |
| `Collector.java` | 공공 API 원본을 `data/raw`에 저장 (키는 환경변수, 이어받기) |
| `Csv.java` | UTF-8(BOM) CSV 읽기·쓰기 |
| `RecipeRaw.java` | 레시피 원본 JSON 읽기 |
| `FoodDetailParser.java` / `FoodRecord.java` | 국가표준식품 상세 응답 파싱과 한 건 |
| `FoodSources.java` | 식품 출처 합치기(국가표준 + 수산물 보충) |
| `IngredientParser.java` / `ParsedIngredient.java` | 재료 문자열 → 이름·수량·단위 (규칙) |
| `IngredientStats.java` | 재료 문자열 통계 리포트 |
| `IngredientMatching.java` | 매칭·단위 환산표·커버리지 리포트 생성 |
| `FoodMatcher.java` | 재료명 → 식품 코드 (EXACT/MANUAL_CONFIRMED/SIMILAR/UNMATCHED) |
| `UnitConversions.java` | 재료×단위 → g (원문 병기 중앙값만) |
| `IngredientResolver.java` | 파싱 → g 확정 → 매칭을 한 번에 (DB 적재·오프라인 공용) |
| `IngredientAnalyzer.java` | 회원 입력 재료를 같은 규칙으로 분석 |
| `GoldSetAccuracy.java` | 사람이 확인한 정답셋과 파서 비교 |
| `NutritionCheck.java` | DB 없이 전체 레시피 계산 커버리지 확인 |
| `RoleReport.java` | 기름·설탕 역할 분포와 확인 표본(O/X) |
| `FoodLoader.java` | 식품·영양성분 DB 적재 (JDBC 일괄) |
| `RecipeLoader.java` | 레시피·조리 순서·재료 적재 |
| `IngredientAnalysisWriter.java` | 재료 역할 분석을 DB에 저장 |
| `MealItemLoader.java` | 식사 조합 후보(편의점·외식·직접 조리) 적재 |
| `GeminiClient.java` | Gemini 호출 (공개 데이터만, 429/503/500·시간초과 재시도) |
| `AiReview.java` | 매칭·중량 제안을 Gemini로 독립 교차검토 |
| `AiTranslate.java` | 레시피·재료·식품·식사 후보 이름, 조리 순서·팁을 영어로 일괄 번역 (묶음 자동 축소, 잘린 답 살리기, 하루 한도에서 멈춤) |

## 11. 테스트 (`src/test/java/com/springboot/`)

| 파일 | 확인하는 것 |
|---|---|
| `HealthMealApplicationTests.java` | 앱 컨텍스트가 뜬다 |
| `checkup/CheckupExtractorTest.java` | PDF에서 항목 추출, 참고치 대신 결과값, 두 줄 혈압·다음 줄 값, HDL/LDL 구분 |
| `checkup/CheckupRulesTest.java` | 판정 경계값, 성별 허리둘레, 상담 우선, 신장 주의 |
| `checkup/GeminiCheckupVisionTest.java` | AI 답 범위 검사, 혈압 쌍 검사, 코드블록·미래 날짜, 한도 응답 처리 |
| `config/LoggingConfigTest.java` | 요청 URL·소요 시간이 로그 파일에 남는다 |
| `controller/CheckupFileTest.java` | 업로드 권한·형식 거부·다른 회원 파일 차단 |
| `controller/HealthCheckupFlowTest.java` | 추출 → 확인 → 암호화 저장 → 목표 → 식사 조합 전체 흐름 |
| `controller/MemberControllerTest.java` | 비회원 접근, 가입 검증, 로그인 이동 |
| `goal/GoalRulesTest.java` | 에너지필요추정량, 검진 → 주·보조 목표 |
| `i18n/TranslatorTest.java` | 한국어는 그대로, 영어는 문장 틀·사전·괄호 단위 재료 번역 |
| `nutrition/NutritionEngineTest.java` | 합계·부분 커버리지·변화율 상한 |
| `nutrition/IngredientAnalysisTest.java` | 조리·영양 역할 판정 |
| `nutrition/IngredientRoleClassifierTest.java` | 기름·설탕 역할을 조리 문장으로 구분 |
| `pipeline/AiReviewTest.java` | 교차검토 결과 판정 |
| `pipeline/AiTranslateTest.java` | 번역 대상 수집, 잘린 JSON 살리기 |
| `pipeline/CollectorTest.java` | API 응답 파싱, 재시도 대상 |
| `pipeline/EnglishPagesTest.java` | 영어로 24개 화면을 그려 한국어 문구가 없고 모든 POST 폼에 CSRF가 있다 |
| `pipeline/FoodLoaderTest.java` | 웹 없이 H2에 식품 적재 |
| `pipeline/FoodMatchingTest.java` | 매칭 등급·동의어·단위 환산 |
| `pipeline/FoodSourcesTest.java` | 보충 식품 사용 조건 |
| `pipeline/GoldSetAccuracyTest.java` | 정답셋 정확도 유지 |
| `pipeline/IngredientParserTest.java` | 실제 원문 사례별 파싱 규칙 |
| `pipeline/RecipePipelineIntegrationTest.java` | 전체 원본 적재, DB·오프라인 계산 일치, 화면·API·즐겨찾기·식사 조합 |
| `service/CheckupFileServiceTest.java` | 파일 이름 정리 |
| `service/MealPlannerTest.java` | 한 끼 목표, 점수순, 제외 식품, 정보 부족, 페이지 |
| `service/MemberServiceTest.java` | 비밀번호 암호화, 중복 가입 거부 |
| `service/SubstitutionRulesTest.java` | 부위 대체 조건, 다른 영양소 악화 경고, 맛 영향 |
| `service/SubstitutionServiceTest.java` | 달성 판정, 조사 붙이기, 대체 규칙 읽기 |
| `../js/nutrition-game.test.js` | 게임 규칙(node로 실행): 난이도별 비율 구간, 식품 중복 없음, 정답 판정, 점수·콤보, 목숨, 최고 기록 |

## 12. 리소스 (`src/main/resources/`)

| 파일 | 설명 |
|---|---|
| `application.properties` | DB·업로드·메시지·로그 설정. `.env`를 읽어 환경변수로 키를 받는다 |
| `checkup-rules.csv` | 검진 판정 기준값과 출처 |
| `logging/log4j2.xml` | 12장 Log4j2: 콘솔 + `logs/monitor.log` |
| `messages/messages.properties`, `messages_ko.properties`, `messages_en.properties` | 8장 화면 고정 문구(한국어·영어) |
| `messages/sentences_en.txt` | 서버가 만드는 문장의 영어 틀 (TAB 구분, `{0}`) |
| `messages/data_en.csv` | 영양소·분류·조리법·단위·부위 등 데이터 이름 영어 사전 |
| `static/css/style.css` | 전체 화면 스타일 |
| `static/js/favorite.js` | 레시피 상세 별표 즐겨찾기(REST API 호출) |
| `static/js/loading.js` | 파일 처리·AI 판독 중 로딩창 |
| `static/js/nutrition-game.js` | 영양 퀴즈 게임 본체: 문제 만들기(난이도별 비율), 시간 제한, 목숨, 콤보 점수, 최고 기록(localStorage), 오답 복습, 대기 화면용 `NutriGame.end()` |
| `templates/fragments/header.html` | 공통 머리말(메뉴·로그인·언어 전환, `sec:authorize`) |
| `templates/index.html` | 홈 |
| `templates/game.html` | 영양 퀴즈 게임 화면 |
| `templates/fragments/game.html` | 게임 본체(설정·문제·결과 패널, 화면 문구 JSON). 게임 화면과 AI 대기 화면이 함께 쓴다 |
| `templates/signup.html` / `login.html` | 회원가입 / 로그인 |
| `templates/recipes/list.html` | 레시피 검색 목록 |
| `templates/recipes/detail.html` | 레시피 상세: 영양·재료·조리 순서·감량/대체 후보·별표 |
| `templates/recipes/adjust.html` | 감량·대체 적용 결과와 맞춤 레시피 저장 |
| `templates/myrecipes/form.html` | 개인 레시피 등록·수정과 재료 분석 |
| `templates/meals.html` | 식사 조합 추천과 저장 |
| `templates/mypage.html` | 마이페이지(프로필·저장 목록·즐겨찾기·검진 파일) |
| `templates/goals/profile.html` / `goals.html` | 프로필 입력 / 개인 영양 목표 |
| `templates/health/form.html` | 검진 수치 확인·입력 |
| `templates/health/ai-wait.html` | 사진 AI 판독 대기 화면: 위에 영양 퀴즈 게임, 아래에 판독 경과. 끝나면 알림과 "게임 끝내고 결과 보기" 버튼 |
| `templates/health/result.html` | 검진 판정과 추천 목표 |
| `templates/error/404.html`, `common.html`, `recipe.html` | 11장 오류 화면 (없는 페이지, 공통 오류, 없는 레시피) |

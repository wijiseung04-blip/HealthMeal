package com.springboot.pipeline;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 공공 API 원본 응답을 data/raw 아래에 그대로 저장한다.
 * 키는 환경변수에서만 읽고, 저장 파일에는 키가 들어가지 않는다.
 * 이미 받은 파일은 건너뛰므로 중단 후 다시 실행하면 이어서 받는다.
 */
public class Collector {

	static final Path RAW = Path.of("data", "raw");
	private static final String COOKRCP_URL = "http://openapi.foodsafetykorea.go.kr/api/%s/COOKRCP01/json/%d/%d";
	private static final String FOOD_LIST_URL = "https://apis.data.go.kr/1390803/AgriFood/NationStdFood1/getKoreanFoodNationStdList1?serviceKey=%s&page_No=%d&Page_Size=%d&fd_Grupp=%s";
	private static final String FOOD_DETAIL_URL = "https://apis.data.go.kr/1390803/AgriFood/NationStdFood1/getKoreanFoodNationStdIdntList1?serviceKey=%s&food_Code=%s";
	private static final int RECIPE_CHUNK = 1000; // COOKRCP01 한 번에 최대 1000건
	private static final int FOOD_PAGE_SIZE = 10; // 100은 '요청 페이지 형식 오류'(400)

	private final List<String> targets;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

	Collector(String[] targets) {
		this.targets = targets.length == 0 ? List.of("recipes", "foods") : List.of(targets);
	}

	void run() throws Exception {
		if (targets.contains("recipes")) collectRecipes(requireEnv("FOODSAFETY_API_KEY"));
		if (targets.contains("foods")) collectFoods(URLEncoder.encode(requireEnv("DATA_GO_KR_API_KEY"), StandardCharsets.UTF_8));
		System.out.println("수집 완료: " + RAW.toAbsolutePath());
	}

	// ---------- COOKRCP01 레시피 ----------

	private void collectRecipes(String key) throws Exception {
		Path dir = Files.createDirectories(RAW.resolve("cookrcp01"));
		String first = get(COOKRCP_URL.formatted(key, 1, 1));
		checkCookrcpResult(first);
		int total = parseTotalCount(first);
		System.out.println("COOKRCP01 전체 " + total + "건");
		for (int start = 1; start <= total; start += RECIPE_CHUNK) {
			int end = Math.min(start + RECIPE_CHUNK - 1, total);
			Path file = dir.resolve("cookrcp01_%04d_%04d.json".formatted(start, end));
			if (Files.exists(file)) continue;
			String body = get(COOKRCP_URL.formatted(key, start, end));
			checkCookrcpResult(body);
			Files.writeString(file, body);
			System.out.println("  저장 " + file.getFileName());
		}
	}

	static int parseTotalCount(String body) {
		Matcher m = Pattern.compile("\"total_count\"\\s*:\\s*\"?(\\d+)").matcher(body);
		if (!m.find()) throw new IllegalStateException("total_count를 찾을 수 없음: " + head(body));
		return Integer.parseInt(m.group(1));
	}

	private static void checkCookrcpResult(String body) {
		// 정상 코드는 INFO-000, 데이터 없음은 INFO-200
		Matcher m = Pattern.compile("\"CODE\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
		if (m.find() && !m.group(1).equals("INFO-000")) {
			throw new IllegalStateException("COOKRCP01 오류 " + m.group(1) + ": " + head(body));
		}
	}

	// ---------- 국가표준식품성분 ----------

	private void collectFoods(String encodedKey) throws Exception {
		Path listDir = Files.createDirectories(RAW.resolve("food-list"));
		Path detailDir = Files.createDirectories(RAW.resolve("food-detail"));
		Set<String> codes = new LinkedHashSet<>();

		// 식품군 문자 범위를 몰라 A~Z를 모두 조회하고, 결과가 없는 식품군은 건너뛴다
		for (char group = 'A'; group <= 'Z'; group++) {
			int groupCount = 0;
			for (int page = 1; ; page++) {
				Path file = listDir.resolve("%s_%03d.txt".formatted(group, page));
				String body = readOrFetch(file, FOOD_LIST_URL.formatted(encodedKey, page, FOOD_PAGE_SIZE, group));
				if (body == null) {
					break;
				}
				Set<String> found = extractFoodCodes(body);
				codes.addAll(found);
				groupCount += found.size();
				// total_Count만큼 받았거나 빈 페이지면 다음 식품군
				if (found.isEmpty() || groupCount >= parseFoodTotalCount(body)) break;
			}
			if (groupCount > 0) System.out.println("  식품군 " + group + ": " + groupCount + "개");
		}
		System.out.println("식품 코드 " + codes.size() + "개");
		if (codes.isEmpty()) {
			throw new IllegalStateException("식품 코드를 하나도 찾지 못함. 위에 출력된 응답 오류 메시지를 확인하세요.");
		}

		int done = 0, failed = 0;
		for (String code : codes) {
			if (readOrFetch(detailDir.resolve(code + ".txt"), FOOD_DETAIL_URL.formatted(encodedKey, code)) == null) failed++;
			if (++done % 200 == 0) System.out.println("  상세 " + done + "/" + codes.size());
		}
		System.out.println("식품 상세 " + (done - failed) + "/" + codes.size() + "건 저장" + (failed > 0 ? ", 실패 " + failed + "건 (다시 실행하면 재시도)" : ""));
	}

	/**
	 * 정상 응답만 파일로 남긴다. 저장된 파일이 오류 응답이면 다시 받는다.
	 * 결과 코드가 200이 아니면 메시지를 출력하고 null을 돌려준다.
	 */
	private String readOrFetch(Path file, String url) throws IOException, InterruptedException {
		if (Files.exists(file)) {
			String cached = Files.readString(file);
			if (isOk(cached)) return cached;
		}
		String body = get(url);
		pause();
		checkDataGoKrResult(body);
		if (!isOk(body)) {
			System.out.println("  " + file.getFileName() + ": " + resultCode(body) + " " + resultMsg(body));
			Files.deleteIfExists(file);
			return null;
		}
		Files.writeString(file, body);
		return body;
	}

	static boolean isOk(String body) {
		String code = resultCode(body);
		return code == null || code.equals("200") || code.equals("00");
	}

	static String resultCode(String body) {
		Matcher m = Pattern.compile("<result_Code>\\s*([^<\\s]+)\\s*</result_Code>").matcher(body);
		return m.find() ? m.group(1) : null;
	}

	private static String resultMsg(String body) {
		Matcher m = Pattern.compile("<result_Msg>([^<]*)</result_Msg>").matcher(body);
		return m.find() ? m.group(1).trim() : "";
	}

	/** 목록 응답 XML에서 식품코드를 순서대로 뽑는다. 예: <food_Code>A001001A010a</food_Code> */
	static Set<String> extractFoodCodes(String body) {
		Matcher m = Pattern.compile("<food_Code>\\s*([^<\\s]+)\\s*</food_Code>").matcher(body);
		Set<String> codes = new LinkedHashSet<>();
		while (m.find()) codes.add(m.group(1));
		return codes;
	}

	static int parseFoodTotalCount(String body) {
		Matcher m = Pattern.compile("<total_Count>\\s*(\\d+)\\s*</total_Count>").matcher(body);
		return m.find() ? Integer.parseInt(m.group(1)) : 0;
	}

	private static void checkDataGoKrResult(String body) {
		// 키 오류 등은 returnAuthMsg 또는 SERVICE_KEY 문구로 온다
		if (body.contains("LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS")) {
			throw new IllegalStateException("공공데이터포털 하루 호출 한도 초과. 내일 같은 명령을 다시 실행하면 이어서 받습니다.");
		}
		if (body.contains("returnAuthMsg") || body.contains("SERVICE_KEY")) {
			throw new IllegalStateException("공공데이터포털 오류: " + head(body));
		}
	}

	// ---------- 공통 ----------

	private String get(String url) throws IOException, InterruptedException {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build();
		IOException last = null;
		for (int attempt = 1; attempt <= 6; attempt++) {
			try {
				HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
				boolean throttled = res.statusCode() == 429 || isPerSecondLimit(res.body());
				if (res.statusCode() == 200 && !throttled) return res.body();
				last = new IOException("HTTP " + res.statusCode() + ": " + head(res.body()));
				if (!throttled && res.statusCode() < 500) throw last; // 키 오류 등은 재시도해도 같다
			} catch (IOException e) {
				if (e == last) throw e;
				last = e;
			}
			long wait = 5000L << (attempt - 1); // 5, 10, 20, 40, 80초
			System.out.println("  요청 제한 또는 일시 오류, " + wait / 1000 + "초 후 재시도 (" + attempt + "/6)");
			Thread.sleep(wait);
		}
		throw last;
	}

	/** 초당 요청 제한. 하루 한도 초과(LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR)와는 다르다 */
	static boolean isPerSecondLimit(String body) {
		return body.contains("PER_SECOND_EXCEEDS");
	}

	private static void pause() throws InterruptedException {
		Thread.sleep(300); // 공공 API 호출 간격 (초당 제한 회피)
	}

	private static String requireEnv(String name) {
		String v = System.getenv(name);
		if (v == null || v.isBlank()) throw new IllegalStateException("환경변수 " + name + "가 설정되지 않았습니다.");
		return v.trim();
	}

	private static String head(String s) {
		return s.length() > 500 ? s.substring(0, 500) + "..." : s;
	}
}

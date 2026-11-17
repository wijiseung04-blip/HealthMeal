package com.springboot.checkup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

/**
 * Gemini로 검진 결과표 사진을 읽는다. 키는 환경변수 GEMINI_API_KEY (모델은 GEMINI_MODEL).
 * 보내는 것은 회원이 고른 파일 한 장뿐이고 회원 정보는 붙이지 않는다. 요청·응답 내용은 로그에 남기지 않는다.
 * 원문에 없는 값을 만들지 않도록 지시하고, 받은 값은 항목별 가능한 범위로 다시 거른다.
 */
@Slf4j
@Component
public class GeminiCheckupVision implements CheckupVision {

	private static final String URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final String key;
	private final String model;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

	public GeminiCheckupVision(@Value("${GEMINI_API_KEY:}") String key, @Value("${GEMINI_MODEL:gemini-4-hydrogen}") String model) {
		this.key = key == null ? "" : key.trim();
		this.model = model == null || model.isBlank() ? "gemini-4-hydrogen" : model.trim();
	}

	@Override
	public boolean available() {
		return !key.isEmpty();
	}

	static String prompt() {
		return """
				이 이미지는 한국 국민건강보험 건강검진 결과표입니다. 아래 검사 항목의 '본인 결과값'만 읽어 JSON으로 답하세요.
				- 참고치·정상범위·판정 기준·이전 연도 값은 읽지 마세요.
				- 표에 없거나 흐려서 확실하지 않은 항목은 넣지 마세요. 추측하지 마세요.
				- 혈압이 "128/82"처럼 함께 있으면 SYSTOLIC_BP=128, DIASTOLIC_BP=82
				- 숫자만 (단위·부등호 제외). 검진일이 보이면 YYYY-MM-DD
				형식: {"examDate": "2026-10-15" 또는 null, "values": {"항목키": 숫자}, "lines": {"항목키": "그 값을 읽은 표의 행 글자"}}
				항목키:
				""" + String.join("\n", CheckupVision.itemGuide().entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList());
	}

	@Override
	public CheckupExtractor.Result read(byte[] data, String mimeType) throws IOException, InterruptedException {
		if (!available()) throw new IllegalStateException("GEMINI_API_KEY가 설정되지 않았습니다.");
		String body = JSON.writeValueAsString(Map.of(
				"contents", List.of(Map.of("parts", List.of(
						Map.of("inline_data", Map.of("mime_type", mimeType, "data", Base64.getEncoder().encodeToString(data))),
						Map.of("text", prompt())))),
				"generationConfig", Map.of("responseMimeType", "application/json", "temperature", 0)));
		HttpRequest req = HttpRequest.newBuilder(URI.create(URL.formatted(model)))
				.timeout(Duration.ofSeconds(90))
				.header("Content-Type", "application/json")
				.header("x-goog-api-key", key)
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
		for (int attempt = 1; ; attempt++) {
			HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (res.statusCode() == 200) {
				JsonNode text = JSON.readTree(res.body()).path("candidates").path(0).path("content").path("parts").path(0).path("text");
				if (text.isMissingNode()) throw new IOException("Gemini 응답에 본문이 없습니다");
				return parse(text.asString());
			}
			if ((res.statusCode() == 429 || res.statusCode() == 503) && attempt < 3) {
				Thread.sleep(5_000L * attempt); // 화면에서 기다리는 요청이라 짧게만
				continue;
			}
			log.warn("Gemini 검진 판독 실패: status={}", res.statusCode()); // 응답 본문은 기록하지 않는다
			throw new IOException("Gemini 오류 " + res.statusCode());
		}
	}

	/** Gemini 답(JSON) → 추출 결과. 모르는 항목·범위 밖 값·숫자가 아닌 값은 버린다 */
	static CheckupExtractor.Result parse(String answer) throws IOException {
		JsonNode root = JSON.readTree(answer.strip().replaceAll("^```(?:json)?|```$", ""));
		Map<TestItem, CheckupExtractor.Extracted> found = new EnumMap<>(TestItem.class);
		JsonNode values = root.path("values"), lines = root.path("lines");
		for (TestItem item : TestItem.values()) {
			JsonNode v = values.path(item.name());
			Double d = v.isNumber() ? Double.valueOf(v.asDouble()) : number(v.asString(""));
			if (d == null || !item.plausible(d)) continue;
			String line = lines.path(item.name()).asString("");
			found.put(item, new CheckupExtractor.Extracted(item, d, line.length() > 200 ? line.substring(0, 200) : line));
		}
		// 혈압은 둘이 함께, 수축기가 더 커야 한다
		var sys = found.get(TestItem.SYSTOLIC_BP);
		var dia = found.get(TestItem.DIASTOLIC_BP);
		if (sys == null || dia == null || sys.value() <= dia.value()) {
			found.remove(TestItem.SYSTOLIC_BP);
			found.remove(TestItem.DIASTOLIC_BP);
		}
		LocalDate date = null;
		String ds = root.path("examDate").asString("");
		if (ds.matches("\\d{4}-\\d{2}-\\d{2}")) {
			try {
				date = LocalDate.parse(ds);
				if (date.isAfter(LocalDate.now())) date = null;
			} catch (DateTimeException ignored) {
				// 날짜가 아니면 비워 둔다
			}
		}
		return new CheckupExtractor.Result(true, found, date);
	}

	private static Double number(String s) {
		String t = s.replace(",", "").trim();
		return t.matches("\\d{1,4}(\\.\\d+)?") ? Double.valueOf(t) : null;
	}
}

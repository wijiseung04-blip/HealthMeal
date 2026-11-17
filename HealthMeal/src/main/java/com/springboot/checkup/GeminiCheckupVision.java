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

	/** 한 번 판독에 쓰는 최대 시간 (재시도 포함). 백그라운드 작업이라 화면 요청 시간과 무관 */
	static final Duration BUDGET = Duration.ofSeconds(300);
	/** 표 숫자 읽기에는 깊은 추론이 필요 없어 생각 단계를 낮춘다 (응답 시간 단축). 모델이 받지 않으면 빼고 다시 보낸다 */
	private volatile boolean lowThinking = true;
	/** 이보다 크면 줄여서 보낸다 (긴 변 픽셀). 결과표 글자는 이 정도면 충분히 읽힌다 */
	static final int MAX_SIDE = 1600;

	@Override
	public CheckupExtractor.Result read(byte[] data, String mimeType) throws IOException, InterruptedException {
		if (!available()) throw new IllegalStateException("GEMINI_API_KEY가 설정되지 않았습니다.");
		Image img = shrink(data, mimeType);
		String encoded = Base64.getEncoder().encodeToString(img.data());
		long deadline = System.nanoTime() + BUDGET.toNanos();
		for (int attempt = 1; ; attempt++) {
			long left = Duration.ofNanos(deadline - System.nanoTime()).toSeconds();
			if (left < 10) throw new VisionException(VisionException.Reason.TIMEOUT, 0);
			String body = body(img.mimeType(), encoded, lowThinking);
			HttpRequest req = HttpRequest.newBuilder(URI.create(URL.formatted(model)))
					.timeout(Duration.ofSeconds(left))
					.header("Content-Type", "application/json")
					.header("x-goog-api-key", key)
					.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
			HttpResponse<String> res;
			try {
				res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			} catch (java.net.http.HttpTimeoutException e) {
				throw new VisionException(VisionException.Reason.TIMEOUT, 0);
			}
			if (res.statusCode() == 200) {
				JsonNode text = JSON.readTree(res.body()).path("candidates").path(0).path("content").path("parts").path(0).path("text");
				if (text.isMissingNode()) throw new IOException("Gemini 응답에 본문이 없습니다");
				return parse(text.asString());
			}
			int status = res.statusCode();
			if (status == 400 && lowThinking && res.body().toLowerCase().contains("thinking")) {
				log.warn("Gemini 모델 {}가 생각 단계 설정을 받지 않아 빼고 다시 보냄", model);
				lowThinking = false;
				continue;
			}
			if (status == 429 || status == 503 || status == 500) {
				// 429 응답은 다시 시도할 시각(retryDelay)을 알려 준다. 남은 시간 안이면 기다렸다가 한 번 더
				long wait = status == 429 ? retryDelay(res.body()).orElse(15L) : 5L * attempt;
				long remaining = Duration.ofNanos(deadline - System.nanoTime()).toSeconds();
				log.warn("Gemini 검진 판독 {}: {}초 뒤 재시도 가능 (시도 {})", status, wait, attempt);
				if (attempt < 4 && wait + 15 < remaining) {
					Thread.sleep(wait * 1000);
					continue;
				}
				throw new VisionException(status == 429 ? VisionException.Reason.RATE_LIMIT : VisionException.Reason.BUSY, wait);
			}
			log.warn("Gemini 검진 판독 실패: status={}", status); // 응답 본문은 기록하지 않는다
			throw new VisionException(status == 400 || status == 403 ? VisionException.Reason.KEY : VisionException.Reason.OTHER, 0);
		}
	}

	static String body(String mimeType, String encoded, boolean lowThinking) {
		Map<String, Object> config = new LinkedHashMap<>();
		config.put("responseMimeType", "application/json");
		config.put("temperature", 0);
		if (lowThinking) config.put("thinkingConfig", Map.of("thinkingLevel", "low"));
		return JSON.writeValueAsString(Map.of(
				"contents", List.of(Map.of("parts", List.of(
						Map.of("inline_data", Map.of("mime_type", mimeType, "data", encoded)),
						Map.of("text", prompt())))),
				"generationConfig", config));
	}

	/** 429 본문의 details[].retryDelay ("37s") */
	static Optional<Long> retryDelay(String body) {
		try {
			for (JsonNode d : JSON.readTree(body).path("error").path("details")) {
				String v = d.path("retryDelay").asString("");
				if (v.matches("\\d+(\\.\\d+)?s")) return Optional.of((long) Math.ceil(Double.parseDouble(v.substring(0, v.length() - 1))));
			}
		} catch (RuntimeException ignored) {
			// 형식이 다르면 기본값
		}
		return Optional.empty();
	}

	record Image(byte[] data, String mimeType) {
	}

	/**
	 * 휴대폰 사진(수 MB, 4000px)을 그대로 보내면 업로드·처리가 느리고 무료 한도 토큰도 많이 쓴다.
	 * 긴 변을 1600px로 줄여 JPEG로 보낸다. 읽을 수 없는 형식(WEBP·PDF)은 그대로.
	 */
	static Image shrink(byte[] data, String mimeType) {
		if (!mimeType.equals("image/jpeg") && !mimeType.equals("image/png")) return new Image(data, mimeType);
		try {
			java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data));
			if (src == null) return new Image(data, mimeType);
			int w = src.getWidth(), h = src.getHeight();
			double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(w, h));
			if (scale == 1.0 && data.length <= 1_000_000) return new Image(data, mimeType);
			int nw = Math.max(1, (int) Math.round(w * scale)), nh = Math.max(1, (int) Math.round(h * scale));
			java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(nw, nh, java.awt.image.BufferedImage.TYPE_INT_RGB);
			java.awt.Graphics2D g = out.createGraphics();
			g.setColor(java.awt.Color.WHITE); // PNG 투명 배경
			g.fillRect(0, 0, nw, nh);
			g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(src, 0, 0, nw, nh, null);
			g.dispose();
			var writer = javax.imageio.ImageIO.getImageWritersByFormatName("jpeg").next();
			var param = writer.getDefaultWriteParam();
			param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(0.85f);
			var bytes = new java.io.ByteArrayOutputStream();
			try (var ios = javax.imageio.ImageIO.createImageOutputStream(bytes)) {
				writer.setOutput(ios);
				writer.write(null, new javax.imageio.IIOImage(out, null, null), param);
			} finally {
				writer.dispose();
			}
			return bytes.size() < data.length ? new Image(bytes.toByteArray(), "image/jpeg") : new Image(data, mimeType);
		} catch (IOException | RuntimeException e) {
			return new Image(data, mimeType); // 줄이기 실패하면 원본 그대로
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

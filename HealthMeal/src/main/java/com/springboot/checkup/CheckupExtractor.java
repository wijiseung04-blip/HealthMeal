package com.springboot.checkup;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 건강검진 결과 PDF에서 검사 수치를 규칙으로 뽑는다. AI를 쓰지 않으며 원문에 없는 값은 만들지 않는다.
 * 결과는 후보일 뿐이고 사용자가 확인·수정한 값만 저장한다.
 */
public final class CheckupExtractor {

	public record Extracted(TestItem item, double value, String sourceLine) {
	}

	/** examDate: "검진일 2026-10-15" 같은 표기가 있으면 채운다 */
	public record Result(boolean hasText, Map<TestItem, Extracted> values, LocalDate examDate) {
	}

	private static final Pattern NUMBER = Pattern.compile("(?<![\\d.])(\\d{1,4}(?:\\.\\d+)?)(?![\\d.]*\\s*/\\s*\\d)");
	private static final Pattern EXAM_DATE = Pattern.compile("검진\\s*(?:일|일자|날짜)[^0-9]{0,5}(\\d{4})\\s*[.\\-/년]\\s*(\\d{1,2})\\s*[.\\-/월]\\s*(\\d{1,2})");
	private static final Pattern BLOOD_PRESSURE = Pattern.compile("(?<!\\d)(\\d{2,3})\\s*/\\s*(\\d{2,3})(?!\\d)");

	private CheckupExtractor() {
	}

	public static Result extract(byte[] pdf) throws IOException {
		try (PDDocument doc = Loader.loadPDF(pdf)) {
			PDFTextStripper stripper = new PDFTextStripper();
			stripper.setSortByPosition(true); // 표의 같은 행이 한 줄로 나오도록
			return parse(stripper.getText(doc));
		}
	}

	public static Result parse(String text) {
		Map<TestItem, Extracted> found = new EnumMap<>(TestItem.class);
		if (text == null || text.isBlank()) return new Result(false, found, null);
		LocalDate examDate = null;
		Matcher dm = EXAM_DATE.matcher(text);
		if (dm.find()) {
			try {
				examDate = LocalDate.of(Integer.parseInt(dm.group(1)), Integer.parseInt(dm.group(2)), Integer.parseInt(dm.group(3)));
			} catch (java.time.DateTimeException ignored) {
				// 날짜가 아니면 비워 둔다
			}
		}

		for (String rawLine : text.split("\\R")) {
			String line = rawLine.replace(' ', ' ').trim();
			if (line.isEmpty()) continue;
			String compact = line.replace(" ", "");

			// 혈압은 "128/82" 한 칸에 두 값이 있다
			if (compact.contains("혈압") && !found.containsKey(TestItem.SYSTOLIC_BP)) {
				Matcher m = BLOOD_PRESSURE.matcher(line);
				if (m.find()) {
					double sys = Double.parseDouble(m.group(1)), dia = Double.parseDouble(m.group(2));
					if (TestItem.SYSTOLIC_BP.plausible(sys) && TestItem.DIASTOLIC_BP.plausible(dia) && sys > dia) {
						found.put(TestItem.SYSTOLIC_BP, new Extracted(TestItem.SYSTOLIC_BP, sys, line));
						found.put(TestItem.DIASTOLIC_BP, new Extracted(TestItem.DIASTOLIC_BP, dia, line));
					}
				}
			}
			// 한 줄에 여러 항목이 있을 수 있다 ("AST(SGOT) 22 ALT(SGPT) 18"): 항목 이름 위치 순으로 처리
			for (Hit hit : hits(compact)) {
				if (found.containsKey(hit.item())) continue;
				// 다음 항목 이름 전까지의 첫 숫자 = 결과값 (뒤따르는 참고치는 쓰지 않는다)
				String after = compact.substring(hit.end(), hit.nextStart());
				Matcher m = NUMBER.matcher(after);
				if (m.find()) {
					double v = Double.parseDouble(m.group(1));
					if (hit.item().plausible(v)) found.put(hit.item(), new Extracted(hit.item(), v, line));
				}
			}
		}
		return new Result(true, found, examDate);
	}

	private record Hit(TestItem item, int start, int end, int nextStart) {
	}

	/** 줄에서 항목 이름이 나온 위치들. 긴 동의어를 먼저 맞춰 "HDL"이 "HDL콜레스테롤" 안에서 중복되지 않게 한다 */
	private static List<Hit> hits(String compact) {
		String lower = compact.toLowerCase(Locale.ROOT);
		List<int[]> spans = new ArrayList<>(); // start, end, item ordinal
		for (TestItem item : TestItem.values()) {
			List<String> syns = new ArrayList<>(item.synonyms());
			syns.sort(Comparator.comparingInt(String::length).reversed());
			for (String syn : syns) {
				int at = lower.indexOf(syn.replace(" ", "").toLowerCase(Locale.ROOT));
				if (at < 0) continue;
				int end = at + syn.replace(" ", "").length();
				boolean overlaps = spans.stream().anyMatch(s -> at < s[1] && end > s[0]);
				// "HDL" 같은 짧은 영문은 앞뒤가 영문이면 다른 단어의 일부다
				boolean partOfWord = syn.matches("[A-Za-z-]+") && (at > 0 && Character.isLetter(compact.charAt(at - 1))
						|| end < compact.length() && Character.isLetter(compact.charAt(end)) && !compact.substring(end).startsWith("콜"));
				if (!overlaps && !partOfWord) {
					spans.add(new int[]{at, end, item.ordinal()});
					break;
				}
			}
		}
		spans.sort(Comparator.comparingInt(s -> s[0]));
		List<Hit> hits = new ArrayList<>();
		for (int i = 0; i < spans.size(); i++) {
			int next = i + 1 < spans.size() ? spans.get(i + 1)[0] : compact.length();
			hits.add(new Hit(TestItem.values()[spans.get(i)[2]], spans.get(i)[0], spans.get(i)[1], next));
		}
		return hits;
	}
}

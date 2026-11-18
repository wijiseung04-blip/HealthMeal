package com.springboot.i18n;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 8장 다국어의 확장: 화면 고정 문구는 메시지 리소스(#{키})로 하고, 이 빈은 그 밖의 두 가지를 맡는다.
 * <ul>
 *   <li>{@link #t(String)}: 서버가 계산하며 만든 문장 ("나트륨 520 mg — 한 끼 기준 767 mg 이하"). 숫자가 바뀌므로
 *       messages/sentences_en.txt 의 문장 틀({0}, {1}…)과 맞춰 영어 틀에 끼워 넣는다. 끼워 넣는 값도 다시 번역한다</li>
 *   <li>{@link #d(String)}: 공공 데이터 이름 (레시피·식품·영양소·분류). messages/data_en.csv(직접 만든 사전)와
 *       data/mapping/translations-en.csv(ai-translate로 만든 번역)를 쓴다</li>
 * </ul>
 * 영어가 아니거나 맞는 번역이 없으면 원문(한국어)을 그대로 돌려준다. 화면에서는 ${@tr.t(...)}, ${@tr.d(...)}
 */
@Slf4j
@Component("tr")
public class Translator {

	static final Path DATA_FILE = Path.of("data", "mapping", "translations-en.csv");

	private record Rule(Pattern pattern, String english, int groups) {
	}

	private final List<Rule> rules = new ArrayList<>();
	private final Map<String, String> exact = new HashMap<>();
	private final Map<String, String> data = new HashMap<>();

	public Translator() {
		loadSentences();
		loadData(new ClassPathResource("messages/data_en.csv"));
		if (Files.exists(DATA_FILE)) {
			try (InputStream in = Files.newInputStream(DATA_FILE)) {
				readCsv(in);
			} catch (IOException e) {
				log.warn("데이터 번역 파일을 읽지 못함: {}", DATA_FILE);
			}
		}
		log.info("번역: 문장 틀 {}개, 데이터 이름 {}개", rules.size() + exact.size(), data.size());
	}

	/** 지금 화면 언어가 영어인지 */
	public boolean english() {
		return "en".equals(LocaleContextHolder.getLocale().getLanguage());
	}

	/** 서버가 만든 문장 번역 (영어가 아니면 그대로) */
	public String t(String text) {
		if (text == null || !english() || !hasHangul(text)) return text;
		return translate(text, 0);
	}

	/** 데이터 이름 번역 (영어가 아니면 그대로, 번역이 없으면 원문) */
	public String d(String name) {
		if (name == null || !english() || !hasHangul(name)) return name;
		return dataName(name);
	}

	/** 여러 이름을 쉼표로 이은 것 ("새우, 땅콩") */
	public String list(String names) {
		if (names == null || !english()) return names;
		return String.join(", ", Arrays.stream(names.split("\\s*,\\s*")).map(this::d).toList());
	}

	private String translate(String text, int depth) {
		String s = text.strip();
		if (depth > 4 || !hasHangul(s)) return text;
		String hit = exact.get(s);
		if (hit != null) return hit;
		// "간장 2g → 50% 감량": 화살표 양쪽은 따로 번역한다 (문장 틀이 화살표를 넘어 맞지 않도록)
		if (s.contains(" → ")) {
			return String.join(" → ", Arrays.stream(s.split(" → ")).map(p -> translate(p, depth + 1)).toList());
		}
		String name = data.get(s);
		if (name != null) return name;
		for (Rule r : rules) {
			Matcher m = r.pattern().matcher(s);
			if (!m.matches()) continue;
			String out = r.english();
			for (int g = 1; g <= r.groups(); g++) out = out.replace("{" + (g - 1) + "}", translate(m.group(g), depth + 1));
			return out;
		}
		// 문장 틀에 없으면: " · " 나 ", " 로 이어진 조각을 각각 번역해 본다
		for (String sep : List.of(" · ", " / ", "; ", ", ")) {
			if (s.contains(sep)) {
				return String.join(sep, Arrays.stream(s.split(Pattern.quote(sep))).map(p -> translate(p, depth + 1)).toList());
			}
		}
		String named = dataName(s);
		return named.equals(s) ? ingredient(s) : named;
	}

	private static final Pattern AMOUNT = Pattern.compile("^(.+?)\\s+([0-9][0-9./~]*\\s*(\\S*)|약간|조금|적당량|소량)$");

	/** 재료 한 줄 "간장 2큰술" → 이름은 데이터 번역, 단위는 사전 ("Soy sauce 2 Tbsp"). 이름 번역이 없으면 원문 */
	private String ingredient(String raw) {
		Matcher m = AMOUNT.matcher(raw.strip());
		if (!m.matches()) return raw;
		String name = dataName(m.group(1));
		if (hasHangul(name)) return raw;
		String amount = m.group(2);
		if (m.group(3) != null && !m.group(3).isEmpty() && hasHangul(m.group(3))) {
			String unit = data.get(m.group(3));
			if (unit == null) return raw;
			amount = amount.substring(0, amount.length() - m.group(3).length()).strip() + " " + unit;
		} else if (hasHangul(amount)) {
			amount = data.getOrDefault(amount, amount);
		}
		return name + " " + amount;
	}

	/** 식품 이름 "돼지고기, 안심, 생것": 통째 번역이 없으면 조각별로 */
	private String dataName(String name) {
		String hit = data.get(name.strip());
		if (hit != null) return hit;
		if (name.contains(", ")) {
			List<String> parts = Arrays.stream(name.split(", ")).map(p -> data.getOrDefault(p.strip(), p.strip())).toList();
			if (parts.stream().noneMatch(Translator::hasHangul)) return String.join(", ", parts);
		}
		return name;
	}

	static boolean hasHangul(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c >= '가' && c <= '힣') return true;
		}
		return false;
	}

	// ---- 읽기 ----

	/** 한 줄에 "한국어 틀<TAB>English template". {0}…은 아무 글자, #으로 시작하면 주석 */
	private void loadSentences() {
		try (InputStream in = new ClassPathResource("messages/sentences_en.txt").getInputStream()) {
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
				if (line.isBlank() || line.startsWith("#")) continue;
				String[] kv = line.split("\t");
				if (kv.length != 2) continue;
				String ko = kv[0].strip(), en = kv[1].strip();
				if (!ko.contains("{")) {
					exact.put(ko, en);
					continue;
				}
				StringBuilder regex = new StringBuilder();
				Matcher m = Pattern.compile("\\{(\\d)}").matcher(ko);
				int last = 0, groups = 0;
				while (m.find()) {
					regex.append(Pattern.quote(ko.substring(last, m.start()))).append("(.+?)");
					last = m.end();
					groups++;
				}
				regex.append(Pattern.quote(ko.substring(last)));
				rules.add(new Rule(Pattern.compile(regex.toString(), Pattern.DOTALL), en, groups));
			}
		} catch (IOException e) {
			log.warn("문장 번역 파일을 읽지 못함");
		}
		// 고정된 앞뒤 글자가 긴 틀부터 (더 구체적인 틀이 먼저 맞도록)
		rules.sort(Comparator.comparingInt((Rule r) -> -r.pattern().pattern().replaceAll("\\(\\.\\+\\?\\)|\\\\Q|\\\\E", "").length()));
	}

	private void loadData(ClassPathResource resource) {
		if (!resource.exists()) return;
		try (InputStream in = resource.getInputStream()) {
			readCsv(in);
		} catch (IOException e) {
			log.warn("데이터 사전을 읽지 못함");
		}
	}

	/** type,ko,en (따옴표 CSV) */
	private void readCsv(InputStream in) throws IOException {
		String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("﻿", "");
		for (String line : text.split("\r?\n")) {
			if (line.isBlank()) continue;
			List<String> r = cells(line);
			if (r.size() < 3 || r.get(1).isBlank() || r.get(2).isBlank() || r.get(0).equals("type")) continue;
			data.put(r.get(1).strip(), r.get(2).strip());
		}
	}

	/** 따옴표·쉼표를 처리하는 CSV 한 줄 */
	static List<String> cells(String line) {
		List<String> cells = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < line.length(); i++) {
			char ch = line.charAt(i);
			if (quoted && ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
				cur.append('"');
				i++;
			} else if (ch == '"') {
				quoted = !quoted;
			} else if (ch == ',' && !quoted) {
				cells.add(cur.toString());
				cur.setLength(0);
			} else {
				cur.append(ch);
			}
		}
		cells.add(cur.toString());
		return cells;
	}
}

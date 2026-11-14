package com.springboot.pipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 엑셀에서 한글이 깨지지 않는 UTF-8(BOM) CSV 읽기·쓰기 */
final class Csv {

	private Csv() {
	}

	static void write(Path file, List<String> header, List<List<Object>> rows) throws IOException {
		write(file, header, rows, "\n");
	}

	/** 사람이 고치는 파일은 원래 줄바꿈(엑셀 저장 시 CRLF)을 지켜 diff가 줄 전체로 바뀌지 않게 한다 */
	static void write(Path file, List<String> header, List<List<Object>> rows, String eol) throws IOException {
		Files.createDirectories(file.getParent());
		StringBuilder sb = new StringBuilder("﻿");
		sb.append(line(new ArrayList<>(header)));
		for (List<Object> r : rows) sb.append(line(r));
		Files.writeString(file, eol.equals("\n") ? sb : sb.toString().replace("\n", eol), StandardCharsets.UTF_8);
	}

	static String eolOf(Path file) throws IOException {
		return Files.exists(file) && Files.readString(file, StandardCharsets.UTF_8).contains("\r\n") ? "\r\n" : "\n";
	}

	private static String line(List<?> cells) {
		List<String> out = new ArrayList<>();
		for (Object c : cells) {
			if (c == null) out.add("");
			else if (c instanceof Double d) out.add(num(d));
			else out.add("\"" + c.toString().replace("\"", "\"\"").replace("\n", " ") + "\"");
		}
		return String.join(",", out) + "\n";
	}

	static String num(double d) {
		return d == Math.rint(d) ? String.valueOf((long) d) : String.format("%.4f", d).replaceAll("0+$", "");
	}

	/** 따옴표·쉼표를 처리하는 단순 CSV 읽기 (첫 줄은 헤더로 건너뜀) */
	static List<List<String>> read(Path file) throws IOException {
		return read(file, StandardCharsets.UTF_8);
	}

	static List<List<String>> read(Path file, java.nio.charset.Charset charset) throws IOException {
		List<List<String>> rows = new ArrayList<>();
		if (!Files.exists(file)) return rows;
		String text = Files.readString(file, charset).replace("﻿", "");
		boolean first = true;
		for (String line : text.split("\r?\n")) {
			if (line.isBlank()) continue;
			if (first) {
				first = false;
				continue;
			}
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
					cells.add(cur.toString().trim());
					cur.setLength(0);
				} else {
					cur.append(ch);
				}
			}
			cells.add(cur.toString().trim());
			rows.add(cells);
		}
		return rows;
	}
}

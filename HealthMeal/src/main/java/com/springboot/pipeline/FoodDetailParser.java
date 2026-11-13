package com.springboot.pipeline;

import com.springboot.pipeline.FoodRecord.Nutrient;
import com.springboot.pipeline.FoodRecord.ValueType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** data/raw/food-detail/*.txt (getKoreanFoodNationStdIdntList1 응답 XML)을 읽는다. */
public final class FoodDetailParser {

	private FoodDetailParser() {
	}

	static List<FoodRecord> loadAll(Path dir) throws IOException {
		List<FoodRecord> foods = new ArrayList<>();
		try (Stream<Path> files = Files.list(dir)) {
			for (Path f : files.filter(p -> p.toString().endsWith(".txt")).sorted().toList()) {
				FoodRecord food = parse(Files.readString(f));
				if (food != null) foods.add(food);
			}
		}
		if (foods.isEmpty()) throw new IllegalStateException(dir + "에 식품 상세 원본이 없습니다. 먼저 collect foods를 실행하세요.");
		return foods;
	}

	static FoodRecord parse(String xml) {
		try {
			DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
			f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
			NodeList items = doc.getElementsByTagName("item");
			if (items.getLength() == 0) return null;
			Element item = (Element) items.item(0);
			List<Nutrient> nutrients = new ArrayList<>();
			NodeList tickets = item.getElementsByTagName("irdnttcket");
			for (int i = 0; i < tickets.getLength(); i++) {
				Element t = (Element) tickets.item(i);
				String raw = text(t, "cont_Info");
				nutrients.add(new Nutrient(text(t, "irdnt_Nm"), text(t, "irdnt_Eng_Nm"), text(t, "irdnt_Unit_Nm"), value(raw), type(raw)));
			}
			return new FoodRecord(text(item, "food_Code"), text(item, "food_Nm"), text(item, "food_Grupp"),
					text(item, "origin_Nm"), text(item, "examin_Year"), nutrients);
		} catch (Exception e) {
			throw new IllegalStateException("식품 상세 XML 해석 실패: " + e.getMessage(), e);
		}
	}

	static ValueType type(String raw) {
		String v = raw == null ? "" : raw.trim();
		if (v.isEmpty() || v.equals("-")) return ValueType.NOT_MEASURED;
		if (v.replaceAll("[()]", "").equalsIgnoreCase("tr")) return ValueType.TRACE;
		if (v.startsWith("(")) return ValueType.ESTIMATED;
		return ValueType.MEASURED;
	}

	/** "-" → null, "Tr" → 0, "(1,100)" → 1100. null과 0을 구분한다. */
	static Double value(String raw) {
		return switch (type(raw)) {
			case NOT_MEASURED -> null;
			case TRACE -> 0.0;
			default -> Double.valueOf(raw.trim().replaceAll("[(),]", ""));
		};
	}

	private static String text(Element parent, String tag) {
		NodeList n = parent.getElementsByTagName(tag);
		return n.getLength() == 0 ? null : n.item(0).getTextContent().trim();
	}
}

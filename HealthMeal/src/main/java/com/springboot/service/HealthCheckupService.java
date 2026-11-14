package com.springboot.service;

import com.springboot.checkup.CheckupExtractor;
import com.springboot.checkup.CheckupRules;
import com.springboot.checkup.CheckupRules.Evaluation;
import com.springboot.checkup.TestItem;
import com.springboot.domain.CheckupFile;
import com.springboot.domain.CheckupValue;
import com.springboot.domain.HealthCheckup;
import com.springboot.dto.CheckupForm;
import com.springboot.repository.HealthCheckupRepository;
import com.springboot.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** 검진 PDF 수치 추출 → 사용자 확인 → 암호화 저장 → 판정·목표 추천 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HealthCheckupService {

	private static final CheckupRules RULES = CheckupRules.load();

	private final HealthCheckupRepository checkupRepository;
	private final MemberRepository memberRepository;
	private final CheckupFileService fileService;

	/** 폼 준비 결과. hasText=false면 스캔 PDF 등으로 글자를 읽지 못한 것 */
	public record Prepared(CheckupForm form, boolean fromFile, boolean hasText, int extractedCount, String fileName) {
	}

	public record Summary(Long id, LocalDate examDate, LocalDateTime createdAt, String fileName, int valueCount) {
	}

	public record Detail(Long id, LocalDate examDate, String sex, String fileName, Map<TestItem, Double> values,
						 Map<TestItem, String> sources, Evaluation evaluation) {
	}

	public Optional<Prepared> prepare(String email, Long fileId) throws IOException {
		CheckupForm form = new CheckupForm();
		form.setSex("M");
		if (fileId == null) return Optional.of(new Prepared(form, false, true, 0, null));

		Optional<CheckupFile> file = fileService.find(email, fileId);
		Optional<byte[]> bytes = fileService.readBytes(email, fileId);
		if (file.isEmpty() || bytes.isEmpty()) return Optional.empty();
		CheckupExtractor.Result r;
		try {
			r = CheckupExtractor.extract(bytes.get());
		} catch (IOException e) {
			log.warn("검진 PDF 읽기 실패: fileId={}", fileId); // 내용은 기록하지 않는다
			r = new CheckupExtractor.Result(false, Map.of(), null);
		}
		form.setFileId(fileId);
		form.setExamDate(r.examDate());
		r.values().forEach((item, e) -> {
			String v = format(e.value());
			form.getValues().put(item.name(), v);
			form.getExtracted().put(item.name(), v);
		});
		return Optional.of(new Prepared(form, true, r.hasText(), r.values().size(), file.get().getOriginalName()));
	}

	/** 검증 후 저장. 오류가 있으면 항목별 메시지를 돌려주고 저장하지 않는다 */
	@Transactional
	public Map<String, String> validate(CheckupForm form) {
		Map<String, String> errors = new LinkedHashMap<>();
		if (!"M".equals(form.getSex()) && !"F".equals(form.getSex())) errors.put("sex", "성별을 선택하세요.");
		if (form.getExamDate() != null && form.getExamDate().isAfter(LocalDate.now())) errors.put("examDate", "검진일은 오늘 이후일 수 없습니다.");
		int filled = 0;
		for (TestItem item : TestItem.values()) {
			String raw = form.getValues().getOrDefault(item.name(), "").trim();
			if (raw.isEmpty()) continue;
			filled++;
			try {
				double v = Double.parseDouble(raw.replace(",", ""));
				if (!item.plausible(v)) errors.put(item.name(), item.getLabel() + " 값은 " + item.rangeText() + " " + item.getUnit() + " 사이여야 합니다.");
			} catch (NumberFormatException e) {
				errors.put(item.name(), item.getLabel() + " 값은 숫자로 입력하세요.");
			}
		}
		if (filled == 0) errors.put("values", "수치를 하나 이상 입력하세요.");
		return errors;
	}

	@Transactional
	public Long save(String email, CheckupForm form) {
		var member = memberRepository.findByEmail(email).orElseThrow();
		CheckupFile file = form.getFileId() == null ? null : fileService.find(email, form.getFileId()).orElse(null);
		HealthCheckup checkup = new HealthCheckup(member, file, form.getExamDate(), form.getSex());
		for (TestItem item : TestItem.values()) {
			String raw = form.getValues().getOrDefault(item.name(), "").trim();
			if (raw.isEmpty()) continue;
			double v = Double.parseDouble(raw.replace(",", ""));
			String extracted = form.getExtracted().get(item.name());
			String source = extracted == null ? "USER_ENTERED" : Double.parseDouble(extracted) == v ? "TEXT_EXTRACTED" : "USER_EDITED";
			checkup.addValue(item.name(), v, source);
		}
		Long id = checkupRepository.save(checkup).getId();
		log.info("검진 수치 저장: memberId={}, checkupId={}, 항목 {}개", member.getId(), id, checkup.getValues().size()); // 수치는 기록하지 않는다
		return id;
	}

	public List<Summary> list(String email) {
		return checkupRepository.findByMemberEmailOrderByCreatedAtDesc(email).stream()
				.map(c -> new Summary(c.getId(), c.getExamDate(), c.getCreatedAt(),
						c.getCheckupFile() == null ? null : c.getCheckupFile().getOriginalName(), c.getValues().size()))
				.toList();
	}

	public Optional<Detail> detail(String email, Long id) {
		return checkupRepository.findByIdAndMemberEmail(id, email).map(c -> {
			Map<TestItem, Double> values = new EnumMap<>(TestItem.class);
			Map<TestItem, String> sources = new EnumMap<>(TestItem.class);
			for (CheckupValue v : c.getValues()) {
				values.put(TestItem.valueOf(v.getItem()), v.getValue());
				sources.put(TestItem.valueOf(v.getItem()), v.getSource());
			}
			Evaluation e = RULES.evaluate(values, CheckupRules.Sex.valueOf(c.getSex()));
			return new Detail(c.getId(), c.getExamDate(), c.getSex(),
					c.getCheckupFile() == null ? null : c.getCheckupFile().getOriginalName(), values, sources, e);
		});
	}

	@Transactional
	public boolean delete(String email, Long id) {
		Optional<HealthCheckup> c = checkupRepository.findByIdAndMemberEmail(id, email);
		c.ifPresent(checkupRepository::delete);
		return c.isPresent();
	}

	private static String format(double v) {
		return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
	}
}

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
	private final com.springboot.checkup.CheckupVision vision;

	/**
	 * 폼 준비 결과. hasText=false면 스캔 PDF·사진이라 규칙으로 글자를 읽지 못한 것.
	 * aiUsed: 이번에 AI로 읽었는지, aiAvailable: AI 판독을 쓸 수 있는지(키 설정), aiError: AI 판독 실패 안내
	 */
	public record Prepared(CheckupForm form, boolean fromFile, boolean hasText, int extractedCount, String fileName,
						   boolean image, boolean aiUsed, boolean aiAvailable, String aiError) {

		public static Prepared manual(CheckupForm form) {
			return new Prepared(form, false, true, 0, null, false, false, false, null);
		}

		/** 검증 오류로 폼을 다시 보여줄 때 */
		public static Prepared redisplay(CheckupForm form) {
			return new Prepared(form, form.getFileId() != null, true, form.getExtracted().size(), null, false,
					CheckupForm.AI.equals(form.getExtractMethod()), false, null);
		}
	}

	public record Summary(Long id, LocalDate examDate, LocalDateTime createdAt, String fileName, int valueCount) {
	}

	public record Detail(Long id, LocalDate examDate, String sex, String fileName, Map<TestItem, Double> values,
						 Map<TestItem, String> sources, Evaluation evaluation) {
	}

	public Optional<Prepared> prepare(String username, Long fileId) throws IOException {
		return prepare(username, fileId, false);
	}

	/**
	 * useAi: 회원이 동의하고 AI 판독을 눌렀을 때만 true. 사진, 또는 글자를 읽지 못한 PDF를 Gemini로 읽는다.
	 * 글자가 있는 PDF는 규칙 추출로 충분하므로 AI로 보내지 않는다.
	 */
	public Optional<Prepared> prepare(String username, Long fileId, boolean useAi) throws IOException {
		CheckupForm form = new CheckupForm();
		form.setSex("M");
		if (fileId == null) return Optional.of(Prepared.manual(form));

		Optional<CheckupFile> file = fileService.find(username, fileId);
		Optional<byte[]> bytes = fileService.readBytes(username, fileId);
		if (file.isEmpty() || bytes.isEmpty()) return Optional.empty();
		boolean image = file.get().isImage();
		CheckupExtractor.Result r = new CheckupExtractor.Result(false, Map.of(), null);
		if (!image) {
			try {
				r = CheckupExtractor.extract(bytes.get());
			} catch (IOException e) {
				log.warn("검진 PDF 읽기 실패: fileId={}", fileId); // 내용은 기록하지 않는다
			}
		}
		boolean aiUsed = false;
		String aiError = null;
		if (useAi && !r.hasText()) {
			if (!vision.available()) {
				aiError = "AI 판독이 설정되지 않았습니다(GEMINI_API_KEY). 결과표를 보면서 직접 입력하세요.";
			} else {
				try {
					var kind = CheckupFileService.Kind.ofStored(file.get().getStoredName());
					r = vision.read(bytes.get(), kind.mimeType());
					aiUsed = true;
					form.setExtractMethod(CheckupForm.AI);
					log.info("검진 AI 판독: fileId={}, 항목 {}개", fileId, r.values().size()); // 수치는 기록하지 않는다
				} catch (IOException | RuntimeException e) {
					log.warn("검진 AI 판독 실패: fileId={}, {}", fileId, e.getClass().getSimpleName());
					aiError = "AI 판독에 실패했습니다. 잠시 뒤 다시 시도하거나 직접 입력하세요.";
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					aiError = "AI 판독이 중단되었습니다.";
				}
			}
		}
		form.setFileId(fileId);
		form.setExamDate(r.examDate());
		r.values().forEach((item, e) -> {
			String v = format(e.value());
			form.getValues().put(item.name(), v);
			form.getExtracted().put(item.name(), v);
		});
		return Optional.of(new Prepared(form, true, r.hasText(), r.values().size(), file.get().getOriginalName(),
				image, aiUsed, vision.available(), aiError));
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
	public Long save(String username, CheckupForm form) {
		var member = memberRepository.findByUsername(username).orElseThrow();
		CheckupFile file = form.getFileId() == null ? null : fileService.find(username, form.getFileId()).orElse(null);
		HealthCheckup checkup = new HealthCheckup(member, file, form.getExamDate(), form.getSex());
		for (TestItem item : TestItem.values()) {
			String raw = form.getValues().getOrDefault(item.name(), "").trim();
			if (raw.isEmpty()) continue;
			double v = Double.parseDouble(raw.replace(",", ""));
			String extracted = form.getExtracted().get(item.name());
			String asIs = CheckupForm.AI.equals(form.getExtractMethod()) ? "AI_EXTRACTED" : "TEXT_EXTRACTED";
			String source = extracted == null ? "USER_ENTERED" : Double.parseDouble(extracted) == v ? asIs : "USER_EDITED";
			checkup.addValue(item.name(), v, source);
		}
		Long id = checkupRepository.save(checkup).getId();
		log.info("검진 수치 저장: memberId={}, checkupId={}, 항목 {}개", member.getId(), id, checkup.getValues().size()); // 수치는 기록하지 않는다
		return id;
	}

	public List<Summary> list(String username) {
		return checkupRepository.findByMemberUsernameOrderByCreatedAtDesc(username).stream()
				.map(c -> new Summary(c.getId(), c.getExamDate(), c.getCreatedAt(),
						c.getCheckupFile() == null ? null : c.getCheckupFile().getOriginalName(), c.getValues().size()))
				.toList();
	}

	public Optional<Detail> detail(String username, Long id) {
		return checkupRepository.findByIdAndMemberUsername(id, username).map(c -> {
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
	public boolean delete(String username, Long id) {
		Optional<HealthCheckup> c = checkupRepository.findByIdAndMemberUsername(id, username);
		c.ifPresent(checkupRepository::delete);
		return c.isPresent();
	}

	private static String format(double v) {
		return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
	}
}

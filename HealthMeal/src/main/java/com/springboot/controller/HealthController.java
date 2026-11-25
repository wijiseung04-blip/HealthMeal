package com.springboot.controller;

import com.springboot.checkup.TestItem;
import com.springboot.dto.CheckupForm;
import com.springboot.service.HealthCheckupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import com.springboot.exception.ResourceNotFoundException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;

/** 회원 전용: 건강검진 수치 확인·저장·결과 (매개변수 이름은 이클립스 빌드를 위해 명시) */
@Controller
@RequestMapping("/mypage/health")
@RequiredArgsConstructor
public class HealthController {

	private final HealthCheckupService checkupService;
	private final com.springboot.service.PersonalGoalService goalService;

	/** fileId가 있으면 그 파일에서 수치를 추출해 채운 폼, 없으면 직접 입력 폼 */
	@GetMapping("/new")
	public String form(@RequestParam(name = "fileId", required = false) Long fileId,
					   @RequestParam(name = "ai", defaultValue = "false") boolean ai, Principal principal, Model model) throws IOException {
		return prepared(checkupService.prepare(principal.getName(), fileId, ai), model);
	}

	/** 사진·스캔 PDF를 AI(Gemini)로 읽기 시작. 회원이 동의 문구를 보고 누른 POST로만 보낸다 */
	@PostMapping("/ai")
	public String aiRead(@RequestParam(name = "fileId") Long fileId, @RequestParam(name = "consent", required = false) String consent,
						 Principal principal, Model model) throws IOException {
		if (!"yes".equals(consent)) return prepared(checkupService.prepare(principal.getName(), fileId, false), model);
		var started = checkupService.startAiRead(principal.getName(), fileId)
				.orElseThrow(() -> new ResourceNotFoundException("파일을 찾을 수 없습니다."));
		if (started.state() == HealthCheckupService.AiState.FAILED) {
			var p = checkupService.prepare(principal.getName(), fileId, false).orElseThrow();
			return prepared(java.util.Optional.of(new HealthCheckupService.Prepared(p.form(), p.fromFile(), p.hasText(), p.extractedCount(),
					p.fileName(), p.image(), false, p.aiAvailable(), started.error())), model);
		}
		if (started.state() == HealthCheckupService.AiState.DONE) return "redirect:/mypage/health/new?ai=true&fileId=" + fileId;
		return "redirect:/mypage/health/ai/wait?fileId=" + fileId;
	}

	/** 판독 대기 화면: 몇 초마다 상태를 물어 끝나면 확인 폼으로. 기다리는 동안 영양 퀴즈 게임 */
	@GetMapping("/ai/wait")
	public String aiWait(@RequestParam(name = "fileId") Long fileId, Principal principal, Model model) {
		checkupService.aiStatus(principal.getName(), fileId)
				.orElseThrow(() -> new ResourceNotFoundException("파일을 찾을 수 없습니다."));
		return waitPage(model, "ai", fileId, "/mypage/health/ai/status?fileId=" + fileId, "/mypage/health/new?ai=true&fileId=" + fileId);
	}

	/** PDF 수치 읽기: 백그라운드에서 시작하고 같은 대기 화면(영양 퀴즈 게임 + 진행 상황)으로. 사진은 확인 폼(AI 동의)으로 바로 */
	@GetMapping("/extract")
	public String extract(@RequestParam(name = "fileId") Long fileId, Principal principal, Model model) throws IOException {
		var started = checkupService.startExtract(principal.getName(), fileId)
				.orElseThrow(() -> new ResourceNotFoundException("파일을 찾을 수 없습니다."));
		String formUrl = "/mypage/health/new?fileId=" + fileId;
		if (started.state() == HealthCheckupService.AiState.DONE) return "redirect:" + formUrl;
		return waitPage(model, "pdf", fileId, "/mypage/health/extract/status?fileId=" + fileId, formUrl);
	}

	@GetMapping(value = "/extract/status", produces = "application/json")
	@ResponseBody
	public HealthCheckupService.AiStatus extractStatus(@RequestParam(name = "fileId") Long fileId, Principal principal) {
		return checkupService.extractStatus(principal.getName(), fileId)
				.orElseThrow(() -> new ResourceNotFoundException("파일을 찾을 수 없습니다."));
	}

	/** mode: ai(사진 AI 판독) / pdf(PDF 수치 읽기). 화면 문구 키의 앞부분이 된다 */
	private static String waitPage(Model model, String mode, Long fileId, String statusUrl, String formUrl) {
		model.addAttribute("mode", mode);
		model.addAttribute("fileId", fileId);
		model.addAttribute("statusUrl", statusUrl);
		model.addAttribute("formUrl", formUrl);
		return "health/ai-wait";
	}

	@GetMapping(value = "/ai/status", produces = "application/json")
	@ResponseBody
	public HealthCheckupService.AiStatus aiStatus(@RequestParam(name = "fileId") Long fileId, Principal principal) {
		return checkupService.aiStatus(principal.getName(), fileId)
				.orElseThrow(() -> new ResourceNotFoundException("파일을 찾을 수 없습니다."));
	}

	private String prepared(java.util.Optional<HealthCheckupService.Prepared> found, Model model) {
		var prepared = found
				.orElseThrow(() -> new ResourceNotFoundException("파일을 찾을 수 없습니다."));
		model.addAttribute("prepared", prepared);
		model.addAttribute("checkupForm", prepared.form());
		model.addAttribute("items", TestItem.values());
		model.addAttribute("errors", Map.of());
		return "health/form";
	}

	@PostMapping
	public String save(@ModelAttribute("checkupForm") CheckupForm form, Principal principal, Model model) {
		Map<String, String> errors = checkupService.validate(form);
		if (!errors.isEmpty()) {
			model.addAttribute("prepared", HealthCheckupService.Prepared.redisplay(form));
			model.addAttribute("items", TestItem.values());
			model.addAttribute("errors", errors);
			return "health/form";
		}
		Long id = checkupService.save(principal.getName(), form);
		goalService.regenerate(principal.getName()); // 새 검진 결과로 목표를 다시 만든다 (직접 고친 목표는 유지)
		return "redirect:/mypage/health/" + id;
	}

	@GetMapping("/{id}")
	public String result(@PathVariable("id") Long id, Principal principal, Model model) {
		model.addAttribute("checkup", checkupService.detail(principal.getName(), id)
				.orElseThrow(() -> new ResourceNotFoundException("검진 결과를 찾을 수 없습니다.")));
		return "health/result";
	}

	@PostMapping("/{id}/delete")
	public String delete(@PathVariable("id") Long id, Principal principal, RedirectAttributes redirect) {
		redirect.addFlashAttribute("message", checkupService.delete(principal.getName(), id) ? "검진 결과를 삭제했습니다." : "검진 결과를 찾을 수 없습니다.");
		return "redirect:/mypage";
	}
}

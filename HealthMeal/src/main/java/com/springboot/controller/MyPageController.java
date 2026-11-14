package com.springboot.controller;

import com.springboot.exception.InvalidUploadException;
import com.springboot.service.CheckupFileService;
import com.springboot.service.HealthCheckupService;
import com.springboot.service.SavedRecipeService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;

/** 회원 전용: 마이페이지와 건강검진 PDF 업로드·다운로드·삭제 */
@Controller
@RequestMapping("/mypage")
@RequiredArgsConstructor
public class MyPageController {

	private final CheckupFileService fileService;
	private final HealthCheckupService checkupService;
	private final SavedRecipeService savedRecipeService;

	@GetMapping
	public String mypage(Principal principal, Model model) {
		model.addAttribute("files", fileService.list(principal.getName()));
		model.addAttribute("checkups", checkupService.list(principal.getName()));
		model.addAttribute("savedRecipes", savedRecipeService.list(principal.getName()));
		return "mypage";
	}

	@PostMapping("/checkups")
	public String upload(@RequestParam(name = "file", required = false) MultipartFile file, Principal principal,
						 RedirectAttributes redirect) throws IOException {
		try {
			fileService.upload(principal.getName(), file);
			redirect.addFlashAttribute("message", "건강검진 결과를 올렸습니다.");
		} catch (InvalidUploadException e) {
			redirect.addFlashAttribute("uploadError", e.getMessage());
		}
		return "redirect:/mypage";
	}

	@GetMapping("/checkups/{id}/file")
	public ResponseEntity<Resource> download(@PathVariable("id") Long id, Principal principal) {
		return fileService.download(principal.getName(), id)
				.map(d -> ResponseEntity.ok()
						.contentType(MediaType.APPLICATION_PDF)
						.contentLength(d.size())
						.header(HttpHeaders.CONTENT_DISPOSITION,
								ContentDisposition.attachment().filename(d.originalName(), StandardCharsets.UTF_8).build().toString())
						.body(d.resource()))
				.orElse(ResponseEntity.notFound().build());
	}

	@PostMapping("/checkups/{id}/delete")
	public String delete(@PathVariable("id") Long id, Principal principal, RedirectAttributes redirect) throws IOException {
		redirect.addFlashAttribute("message", fileService.delete(principal.getName(), id) ? "파일을 삭제했습니다." : "파일을 찾을 수 없습니다.");
		return "redirect:/mypage";
	}

	@PostMapping("/saved/{id}/delete")
	public String deleteSaved(@PathVariable("id") Long id, Principal principal, RedirectAttributes redirect) {
		redirect.addFlashAttribute("message", savedRecipeService.delete(principal.getName(), id) ? "저장한 맞춤 레시피를 삭제했습니다." : "찾을 수 없습니다.");
		return "redirect:/mypage";
	}
}

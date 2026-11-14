package com.springboot.controller;

import com.springboot.dto.MyRecipeForm;
import com.springboot.service.MyRecipeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;

/** 회원 전용: 개인 레시피 등록·수정·삭제. 저장 전 "재료 분석"으로 계산 가능 여부를 미리 본다 */
@Controller
@RequestMapping("/myrecipes")
@RequiredArgsConstructor
public class MyRecipeController {

	private final MyRecipeService service;

	@GetMapping("/new")
	public String newForm(Model model) {
		model.addAttribute("form", new MyRecipeForm());
		return "myrecipes/form";
	}

	/** action=preview면 저장하지 않고 분석 결과만 보여 준다 */
	@PostMapping
	public String create(@Valid @ModelAttribute("form") MyRecipeForm form, BindingResult binding,
						 @RequestParam(name = "action", defaultValue = "save") String action,
						 Principal principal, Model model) {
		if (binding.hasErrors() || "preview".equals(action)) return formWithPreview(form, binding, model, null);
		Long id = service.create(principal.getName(), form);
		return "redirect:/recipes/" + id;
	}

	@GetMapping("/{id}/edit")
	public String editForm(@PathVariable("id") Long id, Principal principal, Model model) {
		model.addAttribute("form", service.form(principal.getName(), id).orElseThrow(MyRecipeController::notFound));
		model.addAttribute("recipeId", id);
		return "myrecipes/form";
	}

	@PostMapping("/{id}")
	public String update(@PathVariable("id") Long id, @Valid @ModelAttribute("form") MyRecipeForm form, BindingResult binding,
						 @RequestParam(name = "action", defaultValue = "save") String action,
						 Principal principal, Model model) {
		if (!service.owns(principal.getName(), id)) throw notFound();
		if (binding.hasErrors() || "preview".equals(action)) return formWithPreview(form, binding, model, id);
		service.update(principal.getName(), id, form);
		return "redirect:/recipes/" + id;
	}

	@PostMapping("/{id}/delete")
	public String delete(@PathVariable("id") Long id, Principal principal, RedirectAttributes redirect) {
		redirect.addFlashAttribute("message", service.delete(principal.getName(), id) ? "레시피를 삭제했습니다." : "레시피를 찾을 수 없습니다.");
		return "redirect:/mypage";
	}

	private String formWithPreview(MyRecipeForm form, BindingResult binding, Model model, Long id) {
		if (!binding.hasFieldErrors("ingredients")) model.addAttribute("preview", service.preview(form.getIngredients()));
		model.addAttribute("recipeId", id);
		return "myrecipes/form";
	}

	private static ResponseStatusException notFound() {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, "레시피를 찾을 수 없습니다.");
	}
}

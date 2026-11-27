package com.springboot.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 홈 화면(/): 검색창과 전체 레시피 링크 */
@Controller
public class HomeController {

	@GetMapping("/")
	public String home() {
		return "index";
	}
}

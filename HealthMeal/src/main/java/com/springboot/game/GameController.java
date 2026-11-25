package com.springboot.game;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/** 영양 퀴즈 게임 화면과 문제 재료 API (비회원도 사용). 게임 자체는 브라우저에서 실행된다 */
@Controller
@RequiredArgsConstructor
public class GameController {

	private final GameService gameService;

	@GetMapping("/game")
	public String page() {
		return "game";
	}

	/** 잘못된 영양소 이름은 400 (enum 변환 실패) */
	@GetMapping("/api/game/foods")
	@ResponseBody
	public GameService.Deck foods(@RequestParam(name = "nutrient", defaultValue = "SODIUM") GameService.Nutrient nutrient) {
		return gameService.deck(nutrient);
	}
}

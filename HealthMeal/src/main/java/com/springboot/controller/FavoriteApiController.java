package com.springboot.controller;

import com.springboot.service.FavoriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * 13장 RESTful 웹 서비스: 즐겨찾기 자원을 HTTP 메서드로 조회·추가·삭제한다 (회원 전용, JSON).
 * GET /api/favorites, PUT /api/favorites/{recipeId}, DELETE /api/favorites/{recipeId}
 */
@RestController
@RequestMapping("/api/favorites")
@RequiredArgsConstructor
public class FavoriteApiController {

	private final FavoriteService favoriteService;

	@GetMapping
	public List<FavoriteService.Row> list(Principal principal) {
		return favoriteService.list(principal.getName());
	}

	@PutMapping("/{recipeId}")
	public ResponseEntity<Map<String, Object>> add(@PathVariable("recipeId") Long recipeId, Principal principal) {
		return favoriteService.add(principal.getName(), recipeId)
				.map(on -> ResponseEntity.ok(Map.<String, Object>of("recipeId", recipeId, "favorite", on)))
				.orElse(ResponseEntity.notFound().build());
	}

	@DeleteMapping("/{recipeId}")
	public ResponseEntity<Map<String, Object>> remove(@PathVariable("recipeId") Long recipeId, Principal principal) {
		return favoriteService.remove(principal.getName(), recipeId)
				.map(on -> ResponseEntity.ok(Map.<String, Object>of("recipeId", recipeId, "favorite", on)))
				.orElse(ResponseEntity.notFound().build());
	}
}

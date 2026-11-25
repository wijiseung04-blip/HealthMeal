/*
 * 영양 퀴즈 게임 (브라우저에서 실행).
 * 두 식품 중 고른 영양소가 더 많은(또는 더 적은) 쪽을 맞힌다. 서버는 문제 재료(/api/game/foods)만 주고,
 * 문제 만들기·시간 제한·목숨·콤보 점수·최고 기록·오답 복습은 모두 이 파일이 한다.
 *
 * 구성: NutriGame.Logic(화면과 무관한 순수 규칙, node로도 시험 가능) + 화면 제어(브라우저에서만)
 */
(function (root) {
	'use strict';

	// ───────────────────────── 1. 규칙 (DOM 없음) ─────────────────────────

	var QUESTIONS = 15;
	var LIVES = 3;

	/** 난이도: 두 값의 비율(큰 값 ÷ 작은 값) 구간과 제한 시간(초), 점수 배율 */
	var LEVELS = {
		EASY: { minRatio: 2.0, maxRatio: Infinity, seconds: 12, multiplier: 1.0 },
		NORMAL: { minRatio: 1.35, maxRatio: 2.0, seconds: 10, multiplier: 1.2 },
		HARD: { minRatio: 1.08, maxRatio: 1.35, seconds: 8, multiplier: 1.5 }
	};

	/** 0 이상 1 미만 난수를 만드는 함수를 씨앗값으로 만든다 (같은 씨앗 → 같은 문제, 시험용) */
	function seeded(seed) {
		var s = seed >>> 0;
		return function () {
			s = (s + 0x6D2B79F5) >>> 0;
			var t = s;
			t = Math.imul(t ^ (t >>> 15), t | 1);
			t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
			return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
		};
	}

	function ratio(a, b) {
		var hi = Math.max(a, b), lo = Math.min(a, b);
		return lo <= 0 ? Infinity : hi / lo;
	}

	/**
	 * 난이도 구간에 맞는 두 식품 짝을 count개 만든다. 한 판에서 같은 식품은 한 번만 쓴다.
	 * 문제마다 "더 많은 쪽"(more=true) 또는 "더 적은 쪽"을 섞는다.
	 * 구간에 맞는 짝이 모자라면 가능한 만큼만 만든다.
	 */
	function makeQuestions(foods, level, count, rnd) {
		var spec = LEVELS[level] || LEVELS.NORMAL;
		var pool = foods.filter(function (f) { return f.value > 0; }).slice();
		// 섞기 (Fisher-Yates)
		for (var i = pool.length - 1; i > 0; i--) {
			var j = Math.floor(rnd() * (i + 1));
			var tmp = pool[i]; pool[i] = pool[j]; pool[j] = tmp;
		}
		var used = {};
		var out = [];
		for (var a = 0; a < pool.length && out.length < count; a++) {
			var fa = pool[a];
			if (used[fa.id]) continue;
			for (var b = a + 1; b < pool.length; b++) {
				var fb = pool[b];
				if (used[fb.id] || fb.value === fa.value) continue;
				var r = ratio(fa.value, fb.value);
				if (r >= spec.minRatio && r < spec.maxRatio) {
					used[fa.id] = used[fb.id] = true;
					var left = rnd() < 0.5;
					out.push({ left: left ? fa : fb, right: left ? fb : fa, more: rnd() < 0.7, ratio: r });
					break;
				}
			}
		}
		return out;
	}

	/** 정답 쪽 ('left' 또는 'right') */
	function answerOf(q) {
		var leftBigger = q.left.value > q.right.value;
		return (leftBigger === q.more) ? 'left' : 'right';
	}

	/** 콤보 배율: 연속 정답마다 0.1씩, 최대 2배 */
	function comboMultiplier(combo) {
		return Math.min(2, 1 + 0.1 * Math.max(0, combo - 1));
	}

	/** 한 문제 점수 = (기본 100 + 남은 초 × 10) × 콤보 배율 × 난이도 배율, 반올림 */
	function points(secondsLeft, combo, level) {
		var spec = LEVELS[level] || LEVELS.NORMAL;
		var base = 100 + Math.max(0, Math.ceil(secondsLeft)) * 10;
		return Math.round(base * comboMultiplier(combo) * spec.multiplier);
	}

	/** 게임 상태. 화면은 이 상태만 보고 그린다 */
	function Game(questions, level) {
		this.questions = questions;
		this.level = level;
		this.index = 0;
		this.lives = LIVES;
		this.score = 0;
		this.combo = 0;
		this.maxCombo = 0;
		this.correct = 0;
		this.history = []; // {q, choice, right, gained}
	}

	Game.prototype.current = function () {
		return this.questions[this.index];
	};

	Game.prototype.over = function () {
		return this.lives <= 0 || this.index >= this.questions.length;
	};

	/** choice: 'left' | 'right' | null(시간 초과). 결과를 돌려주고 다음 문제로 넘어간다 */
	Game.prototype.answer = function (choice, secondsLeft) {
		var q = this.current();
		var right = choice !== null && choice === answerOf(q);
		var gained = 0;
		if (right) {
			this.combo++;
			this.maxCombo = Math.max(this.maxCombo, this.combo);
			this.correct++;
			gained = points(secondsLeft, this.combo, this.level);
			this.score += gained;
		} else {
			this.combo = 0;
			this.lives--;
		}
		var result = { q: q, choice: choice, right: right, gained: gained, answer: answerOf(q) };
		this.history.push(result);
		this.index++;
		return result;
	};

	Game.prototype.wrongAnswers = function () {
		return this.history.filter(function (h) { return !h.right; });
	};

	/** 최고 기록 (영양소 × 난이도별, 이 브라우저에만). 저장소를 못 쓰면 기록 없이 진행 */
	function Records(storage) {
		this.storage = storage;
	}

	Records.prototype.key = function (nutrient, level) {
		return 'healthmeal.game.best.' + nutrient + '.' + level;
	};

	Records.prototype.best = function (nutrient, level) {
		try {
			var v = this.storage && this.storage.getItem(this.key(nutrient, level));
			return v ? parseInt(v, 10) || 0 : 0;
		} catch (e) {
			return 0;
		}
	};

	/** 새 기록이면 저장하고 true */
	Records.prototype.submit = function (nutrient, level, score) {
		var old = this.best(nutrient, level);
		if (score <= old) return false;
		try {
			if (this.storage) this.storage.setItem(this.key(nutrient, level), String(score));
		} catch (e) { /* 저장 못 해도 게임은 계속 */ }
		return true;
	}

	var Logic = {
		QUESTIONS: QUESTIONS, LIVES: LIVES, LEVELS: LEVELS,
		seeded: seeded, ratio: ratio, makeQuestions: makeQuestions, answerOf: answerOf,
		comboMultiplier: comboMultiplier, points: points, Game: Game, Records: Records
	};

	if (typeof module !== 'undefined' && module.exports) {
		module.exports = Logic;
		return;
	}

	// ───────────────────────── 2. 화면 ─────────────────────────

	var doc = root.document;
	var app = doc.getElementById('game');
	if (!app) return;

	var TEXT = JSON.parse(doc.getElementById('game-text').textContent);
	var LANG = app.getAttribute('data-lang') || 'ko';
	// 대기 화면에 끼워 넣은 게임이면 다른 페이지로 가는 링크를 숨긴다 (판독 대기 화면을 떠나지 않게)
	var EMBEDDED = app.getAttribute('data-embedded') === 'true';

	function fmt(key) {
		var s = TEXT[key] || key;
		for (var i = 1; i < arguments.length; i++) s = s.split('{' + (i - 1) + '}').join(String(arguments[i]));
		return s;
	}

	function $(sel) { return app.querySelector(sel); }

	function el(tag, cls, text) {
		var e = doc.createElement(tag);
		if (cls) e.className = cls;
		if (text !== undefined && text !== null) e.textContent = text;
		return e;
	}

	function num(v) {
		return Number(v).toLocaleString(LANG === 'en' ? 'en-US' : 'ko-KR', { maximumFractionDigits: 1 });
	}

	var storage = null;
	try { storage = root.localStorage; } catch (e) { storage = null; }
	var records = new Records(storage);

	var state = {
		nutrient: 'SODIUM', level: 'NORMAL', deck: null, game: null,
		timer: null, deadline: 0, locked: false, finished: false
	};

	function show(panel) {
		['setup', 'play', 'result'].forEach(function (p) {
			$('[data-panel="' + p + '"]').hidden = (p !== panel);
		});
	}

	function message(text, isError) {
		var m = $('[data-role="message"]');
		m.textContent = text || '';
		m.className = isError ? 'error' : 'muted';
	}

	function updateBest() {
		$('[data-role="best"]').textContent = fmt('best', num(records.best(state.nutrient, state.level)));
	}

	// 설정: 영양소·난이도 고르기
	app.querySelectorAll('[data-nutrient]').forEach(function (b) {
		b.addEventListener('click', function () {
			state.nutrient = b.getAttribute('data-nutrient');
			app.querySelectorAll('[data-nutrient]').forEach(function (x) { x.setAttribute('aria-pressed', String(x === b)); });
			updateBest();
		});
	});
	app.querySelectorAll('input[name="level"]').forEach(function (r) {
		r.addEventListener('change', function () {
			state.level = r.value;
			updateBest();
		});
	});

	$('[data-action="start"]').addEventListener('click', start);
	$('[data-action="retry"]').addEventListener('click', start);
	$('[data-action="setup"]').addEventListener('click', function () { updateBest(); show('setup'); });
	$('[data-action="next"]').addEventListener('click', next);
	$('[data-side="left"]').addEventListener('click', function () { choose('left'); });
	$('[data-side="right"]').addEventListener('click', function () { choose('right'); });

	doc.addEventListener('keydown', function (e) {
		if ($('[data-panel="play"]').hidden) return;
		if (e.key === 'ArrowLeft' || e.key === '1') { e.preventDefault(); choose('left'); }
		else if (e.key === 'ArrowRight' || e.key === '2') { e.preventDefault(); choose('right'); }
		else if ((e.key === 'Enter' || e.key === ' ') && state.locked) { e.preventDefault(); next(); }
	});

	function loadDeck(nutrient) {
		if (state.deck && state.deck.nutrient === nutrient) return Promise.resolve(state.deck);
		return fetch('/api/game/foods?nutrient=' + encodeURIComponent(nutrient), { headers: { 'Accept': 'application/json' } })
			.then(function (res) {
				if (!res.ok) throw new Error('HTTP ' + res.status);
				return res.json();
			})
			.then(function (deck) {
				state.deck = deck;
				return deck;
			});
	}

	function start() {
		var button = $('[data-action="start"]');
		button.disabled = true;
		message(TEXT.loading);
		loadDeck(state.nutrient).then(function (deck) {
			var rnd = Math.random;
			var qs = makeQuestions(deck.foods, state.level, QUESTIONS, rnd);
			if (qs.length < 5) {
				message(TEXT.notEnough, true);
				show('setup');
				return;
			}
			state.game = new Game(qs, state.level);
			state.finished = false;
			message('');
			show('play');
			ask();
		}).catch(function () {
			message(TEXT.error, true);
			show('setup');
		}).then(function () {
			button.disabled = false;
		});
	}

	function card(side, food) {
		var c = $('[data-side="' + side + '"]');
		c.querySelector('.name').textContent = food.name;
		var meta = [];
		if (food.maker) meta.push(food.maker);
		meta.push(TEXT['sit.' + food.situation] || food.situation);
		if (food.servingGrams) meta.push(fmt('serving', num(food.servingGrams)));
		c.querySelector('.meta').textContent = meta.join(' · ');
		c.querySelector('.reveal').hidden = true;
		c.classList.remove('right', 'wrong', 'answer');
		c.disabled = false;
	}

	function hud() {
		var g = state.game;
		$('[data-role="progress"]').textContent = fmt('progress', Math.min(g.index + 1, g.questions.length), g.questions.length);
		$('[data-role="score"]').textContent = fmt('score', num(g.score));
		$('[data-role="combo"]').textContent = g.combo > 1 ? fmt('combo', g.combo, comboMultiplier(g.combo).toFixed(1)) : '';
		var hearts = '';
		for (var i = 0; i < LIVES; i++) hearts += i < g.lives ? '♥' : '♡';
		var lives = $('[data-role="lives"]');
		lives.textContent = hearts;
		lives.setAttribute('aria-label', fmt('lives', g.lives));
	}

	function ask() {
		var g = state.game;
		var q = g.current();
		state.locked = false;
		hud();
		var label = TEXT['n.' + state.nutrient];
		$('[data-role="prompt"]').textContent = q.more ? fmt('pickMore', label) : fmt('pickLess', label);
		$('[data-role="prompt"]').className = 'game-prompt ' + (q.more ? 'more' : 'less');
		card('left', q.left);
		card('right', q.right);
		$('[data-role="feedback"]').textContent = '';
		$('[data-action="next"]').hidden = true;
		startTimer(LEVELS[state.level].seconds);
		$('[data-side="left"]').focus();
	}

	function startTimer(seconds) {
		stopTimer();
		state.deadline = Date.now() + seconds * 1000;
		var bar = $('[data-role="timer"]');
		var total = seconds * 1000;
		function tick() {
			var left = state.deadline - Date.now();
			var ratio = Math.max(0, left / total);
			bar.style.width = (ratio * 100) + '%';
			bar.className = 'game-timer-bar' + (ratio < 0.3 ? ' low' : '');
			$('[data-role="seconds"]').textContent = fmt('seconds', Math.max(0, Math.ceil(left / 1000)));
			if (left <= 0) {
				stopTimer();
				choose(null);
			}
		}
		tick();
		state.timer = root.setInterval(tick, 100);
	}

	function stopTimer() {
		if (state.timer) root.clearInterval(state.timer);
		state.timer = null;
	}

	function reveal(side, food, deck, mark) {
		var c = $('[data-side="' + side + '"]');
		var r = c.querySelector('.reveal');
		var pct = deck.daily ? Math.round(food.value / deck.daily * 100) : null;
		r.querySelector('.value').textContent = num(food.value) + ' ' + deck.unit;
		r.querySelector('.daily').textContent = pct === null ? '' : fmt('daily', pct);
		var fill = r.querySelector('.fill');
		fill.style.width = Math.min(100, pct || 0) + '%';
		r.hidden = false;
		if (mark) c.classList.add(mark);
		c.disabled = true;
	}

	function choose(side) {
		if (state.locked || !state.game || state.game.over()) return;
		state.locked = true;
		var secondsLeft = Math.max(0, (state.deadline - Date.now()) / 1000);
		stopTimer();
		var g = state.game;
		var res = g.answer(side, secondsLeft);
		var other = res.answer === 'left' ? 'right' : 'left';
		reveal(res.answer, res.q[res.answer], state.deck, side === res.answer ? 'right' : 'answer');
		reveal(other, res.q[other], state.deck, side === other ? 'wrong' : null);
		var times = res.q.left.value > res.q.right.value ? res.q.left.value / res.q.right.value : res.q.right.value / res.q.left.value;
		var fb = $('[data-role="feedback"]');
		if (res.right) fb.textContent = fmt('correct', num(res.gained)) + ' ' + fmt('times', times.toFixed(1));
		else fb.textContent = (side === null ? TEXT.timeout : TEXT.wrong) + ' ' + fmt('times', times.toFixed(1));
		fb.className = 'game-feedback ' + (res.right ? 'ok' : 'ng');
		hud();
		var nextButton = $('[data-action="next"]');
		nextButton.textContent = g.over() ? TEXT.finish : TEXT.next;
		nextButton.hidden = false;
		nextButton.focus();
	}

	function next() {
		if (!state.locked) return;
		if (state.game.over()) finish();
		else ask();
	}

	function finish() {
		stopTimer();
		var g = state.game;
		state.finished = true;
		var isNew = records.submit(state.nutrient, state.level, g.score);
		$('[data-role="final"]').textContent = fmt('finalScore', num(g.score));
		$('[data-role="newBest"]').hidden = !isNew;
		$('[data-role="summary"]').textContent = fmt('summary', g.correct, g.history.length, g.maxCombo);
		$('[data-role="finalBest"]').textContent = fmt('best', num(records.best(state.nutrient, state.level)));
		var toMeals = $('[data-role="toMeals"]');
		if (toMeals && !EMBEDDED) {
			toMeals.href = '/meals?goal=' + encodeURIComponent(state.deck.goal);
			toMeals.textContent = fmt('toMeals', TEXT['n.' + state.nutrient]);
		}

		var list = $('[data-role="review"]');
		list.textContent = '';
		var wrong = g.wrongAnswers();
		$('[data-role="reviewTitle"]').hidden = wrong.length === 0;
		wrong.forEach(function (h) {
			var li = el('li');
			var label = TEXT['n.' + state.nutrient];
			li.appendChild(el('span', 'muted', (h.q.more ? fmt('pickMore', label) : fmt('pickLess', label)) + ' — '));
			['left', 'right'].forEach(function (side, i) {
				var f = h.q[side];
				var s = el(side === h.answer ? 'strong' : 'span', null, f.name + ' ' + num(f.value) + ' ' + state.deck.unit);
				if (i === 1) li.appendChild(doc.createTextNode(' vs '));
				li.appendChild(s);
			});
			list.appendChild(li);
		});
		show('result');
		$('[data-action="retry"]').focus();
	}

	/** 한 판이라도 시작했는지 (대기 화면: 시작 전이면 판독이 끝날 때 바로 결과로 간다) */
	function started() {
		return state.game !== null;
	}

	/** 게임 끝내기: 진행 중이면 시간을 멈추고 지금까지 점수를 최고 기록에 반영한다. 반영한 점수를 돌려준다 */
	function end() {
		stopTimer();
		state.locked = true;
		if (!state.game) return 0;
		if (!state.finished) {
			state.finished = true;
			records.submit(state.nutrient, state.level, state.game.score);
		}
		app.querySelectorAll('button').forEach(function (b) { b.disabled = true; });
		return state.game.score;
	}

	root.NutriGame = { Logic: Logic, started: started, end: end };
	updateBest();
})(typeof window !== 'undefined' ? window : this);

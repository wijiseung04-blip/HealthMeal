// 영양 퀴즈 게임 규칙 시험 (브라우저 없이): node src/test/js/nutrition-game.test.js
const L = require(process.argv[2] || require("path").join(__dirname, "../../main/resources/static/js/nutrition-game.js"));
const assert = require('assert');
// 가짜 품목 200개 (값 1~1000)
const rnd = L.seeded(42);
const foods = Array.from({length: 200}, (_, i) => ({id: i, name: 'f' + i, value: Math.round(1 + rnd() * 999)}));
for (const level of ['EASY', 'NORMAL', 'HARD']) {
  const qs = L.makeQuestions(foods, level, 15, L.seeded(7));
  assert.strictEqual(qs.length, 15, level);
  const ids = new Set();
  for (const q of qs) {
    const r = L.ratio(q.left.value, q.right.value);
    assert(r >= L.LEVELS[level].minRatio && r < L.LEVELS[level].maxRatio, level + ' ratio ' + r);
    assert(!ids.has(q.left.id) && !ids.has(q.right.id)); ids.add(q.left.id); ids.add(q.right.id);
  }
}
// 같은 씨앗 → 같은 문제
assert.deepStrictEqual(L.makeQuestions(foods, 'HARD', 15, L.seeded(1)), L.makeQuestions(foods, 'HARD', 15, L.seeded(1)));
// 정답 판정
assert.strictEqual(L.answerOf({left: {value: 5}, right: {value: 3}, more: true}), 'left');
assert.strictEqual(L.answerOf({left: {value: 5}, right: {value: 3}, more: false}), 'right');
// 점수: 기본 100 + 남은초*10, 콤보, 난이도 배율
assert.strictEqual(L.points(5, 1, 'EASY'), 150);
assert.strictEqual(L.points(5, 3, 'EASY'), 180);
assert.strictEqual(L.points(0, 1, 'HARD'), 150);
assert.strictEqual(L.comboMultiplier(50), 2);
// 게임: 목숨 3개 다 쓰면 끝
const qs = L.makeQuestions(foods, 'EASY', 15, L.seeded(3));
const g = new L.Game(qs, 'EASY');
const r1 = g.answer(L.answerOf(g.current()), 10);
assert(r1.right && g.score === 200 && g.combo === 1);
const r2 = g.answer(L.answerOf(g.current()), 10);
assert(r2.right && g.combo === 2 && r2.gained === Math.round(200 * 1.1));
g.answer(null, 0); g.answer(L.answerOf(g.current()) === 'left' ? 'right' : 'left', 3);
assert(!g.over() && g.lives === 1 && g.combo === 0);
g.answer(null, 0);
assert(g.over() && g.wrongAnswers().length === 3 && g.correct === 2);
// 기록: 높을 때만 저장, 저장소 오류에도 계속
const mem = {}; const store = {getItem: k => mem[k] ?? null, setItem: (k, v) => { mem[k] = v; }};
const rec = new L.Records(store);
assert(rec.submit('SODIUM', 'EASY', 300) && !rec.submit('SODIUM', 'EASY', 200) && rec.best('SODIUM', 'EASY') === 300);
const broken = new L.Records({getItem() { throw new Error('x'); }, setItem() { throw new Error('x'); }});
assert.strictEqual(broken.best('A', 'B'), 0); assert(broken.submit('A', 'B', 10));
// 모자란 품목: 가능한 만큼만
assert(L.makeQuestions(foods.slice(0, 6), 'HARD', 15, L.seeded(2)).length <= 3);
console.log('game logic OK');

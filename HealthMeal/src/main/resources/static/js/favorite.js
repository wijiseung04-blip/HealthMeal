// 13장: 레시피 상세의 별(☆/★)을 누르면 즐겨찾기 REST API(PUT/DELETE)를 호출한다.
// 자바스크립트가 꺼져 있으면 버튼이 들어 있는 폼(POST)이 그대로 동작한다.
document.addEventListener('DOMContentLoaded', function () {
	var button = document.querySelector('button.star[data-recipe-id]');
	if (!button) return;
	var token = document.querySelector('meta[name="_csrf"]');
	var header = document.querySelector('meta[name="_csrf_header"]');
	button.addEventListener('click', function (event) {
		event.preventDefault();
		var on = button.getAttribute('aria-pressed') === 'true';
		var headers = {'Accept': 'application/json'};
		if (token && header) headers[header.content] = token.content;
		fetch('/api/favorites/' + button.dataset.recipeId, {method: on ? 'DELETE' : 'PUT', headers: headers, credentials: 'same-origin'})
			.then(function (r) { if (!r.ok) throw new Error(r.status); return r.json(); })
			.then(function (data) {
				button.setAttribute('aria-pressed', String(data.favorite));
				button.classList.toggle('on', data.favorite);
				button.textContent = data.favorite ? '★' : '☆';
				var label = data.favorite ? button.dataset.labelRemove : button.dataset.labelAdd;
				button.title = label;
				button.setAttribute('aria-label', label);
				var note = document.getElementById('favorite-note');
				if (note) { note.textContent = data.favorite ? button.dataset.msgAdded : button.dataset.msgRemoved; note.hidden = false; }
			})
			.catch(function () { button.form.submit(); }); // 실패하면 기존 폼 방식으로
	});
});

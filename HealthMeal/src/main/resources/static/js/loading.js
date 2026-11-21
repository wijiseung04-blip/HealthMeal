// 파일 업로드·수치 추출처럼 서버가 처리하는 동안 대기 화면을 띄운다.
// data-loading="안내 문구" 가 붙은 폼(제출)·링크(클릭)에 적용. 뒤로 가기로 돌아오면 닫는다
(function () {
	function overlay(message) {
		var box = document.getElementById('loading-overlay');
		if (!box) {
			box = document.createElement('div');
			box.id = 'loading-overlay';
			box.className = 'ai-wait';
			box.setAttribute('role', 'status');
			box.setAttribute('aria-live', 'polite');
			box.innerHTML = '<div class="ai-wait-box"><div class="spinner" aria-hidden="true"></div><p><strong class="loading-text"></strong></p></div>';
			document.body.appendChild(box);
		}
		box.querySelector('.loading-text').textContent = message;
		box.hidden = false;
	}
	document.addEventListener('submit', function (e) {
		var form = e.target;
		if (!form.dataset || !form.dataset.loading || e.defaultPrevented) return;
		if (typeof form.checkValidity === 'function' && !form.checkValidity()) return;
		overlay(form.dataset.loading);
		form.querySelectorAll('button[type=submit]').forEach(function (b) { b.disabled = true; });
	});
	document.addEventListener('click', function (e) {
		var link = e.target.closest ? e.target.closest('a[data-loading]') : null;
		if (!link || e.ctrlKey || e.metaKey || e.shiftKey) return;
		overlay(link.dataset.loading);
	});
	window.addEventListener('pageshow', function () {
		var box = document.getElementById('loading-overlay');
		if (box) box.hidden = true;
		document.querySelectorAll('form[data-loading] button[type=submit]').forEach(function (b) { b.disabled = false; });
	});
})();

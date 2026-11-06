/* 타로 UI. 외부 라이브러리 없이 바닐라 JS.
 * POST + SSE 라서 EventSource 대신 fetch 의 ReadableStream 으로 이벤트를 직접 파싱한다.
 * 서버가 준 문자열은 항상 textContent 로만 넣는다(innerHTML 사용 안 함). */
(function () {
  'use strict';

  var API = '/api/tarot/readings';
  var MAX_QUESTION = 300;
  var CATEGORY_LABELS = { LOVE: '연애', CAREER: '커리어', MONEY: '재물', DAILY: '오늘의 운세' };
  var SUIT_SYMBOLS = { WANDS: '♣', CUPS: '♥', SWORDS: '♠', PENTACLES: '◆' };
  var TOKEN_PATTERN = /^[A-Za-z0-9_-]{8,64}$/;

  var FLIP_START_MS = 500;
  var FLIP_GAP_MS = 700;
  var FLIP_DURATION_MS = 800;
  var TYPE_INTERVAL_MS = 28;

  var $ = function (id) { return document.getElementById(id); };
  var els = {
    setup: $('setup'), result: $('result'), resultTitle: $('result-title'),
    question: $('question'), count: $('count'), draw: $('draw'), setupMsg: $('setup-msg'),
    cards: $('cards'), interpretation: $('interpretation'), notice: $('notice'),
    copy: $('copy'), again: $('again')
  };
  var categoryButtons = Array.prototype.slice.call(document.querySelectorAll('.cat'));

  var selectedCategory = null;
  var busy = false;
  var controller = null;
  var timers = [];
  var run = null;          // 진행 중인 리딩의 상태
  var sharedToken = null;  // 공유 링크로 열었을 때의 토큰

  // ---------- 작은 도우미 ----------

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) { node.className = className; }
    if (text !== undefined) { node.textContent = text; }
    return node;
  }

  function later(fn, ms) {
    var id = setTimeout(fn, ms);
    timers.push(id);
    return id;
  }

  function clearTimers() {
    timers.forEach(clearTimeout);
    timers = [];
  }

  function show(node, visible) { node.hidden = !visible; }

  function roman(n) {
    if (n === 0) { return '0'; }
    var map = [[10, 'X'], [9, 'IX'], [5, 'V'], [4, 'IV'], [1, 'I']];
    var out = '';
    map.forEach(function (pair) { while (n >= pair[0]) { out += pair[1]; n -= pair[0]; } });
    return out;
  }

  function numberLabel(card) {
    if (card.arcana === 'MAJOR') { return roman(card.number); }
    var named = { 1: 'A', 11: 'P', 12: 'Kn', 13: 'Q', 14: 'K' };
    return named[card.number] || String(card.number);
  }

  function UserFacingError(message) {
    this.message = message;
    this.name = 'UserFacingError';
  }
  UserFacingError.prototype = Object.create(Error.prototype);

  // ---------- 화면 상태 ----------

  function setBusy(value) {
    busy = value;
    categoryButtons.forEach(function (b) { b.disabled = value; });
    els.question.disabled = value;
    updateDrawButton();
  }

  function updateDrawButton() {
    els.draw.disabled = busy || !selectedCategory;
  }

  function showSetupMessage(text) {
    els.setupMsg.textContent = text;
    show(els.setupMsg, !!text);
  }

  function setNotice(text, isError) {
    els.notice.textContent = text;
    els.notice.classList.toggle('error', !!isError);
    show(els.notice, !!text);
  }

  function resetResult() {
    clearTimers();
    typerReset();
    els.cards.textContent = '';
    els.interpretation.textContent = '';
    els.interpretation.classList.remove('typing');
    setNotice('');
    show(els.copy, false);
    show(els.again, false);
    show(els.resultTitle, false);
    els.copy.textContent = '링크 복사';
  }

  function backToSetup() {
    if (controller) { controller.abort(); controller = null; }
    run = null;
    resetResult();
    show(els.result, false);
    show(els.setup, true);
    showSetupMessage('');
    setBusy(false);
    if (location.search) { history.replaceState(null, '', location.pathname); }
  }

  // ---------- 카드 그리기 ----------

  function buildCard(c) {
    var dir = c.reversed ? '역방향' : '정방향';
    var slot = el('div', 'card-slot');
    slot.appendChild(el('div', 'position', c.positionLabel));

    var card = el('div', 'card');
    card.setAttribute('role', 'img');
    card.setAttribute('aria-label', c.positionLabel + ': ' + c.nameKo + ' (' + dir + ')');
    var inner = el('div', 'card-inner');
    inner.appendChild(el('div', 'face back', '✦'));

    var front = el('div', 'face front');
    var art = el('div', c.reversed ? 'art reversed' : 'art');
    var symbolClass = c.arcana === 'MAJOR' ? 'symbol major' : 'symbol ' + c.suit;
    art.appendChild(el('span', symbolClass, c.arcana === 'MAJOR' ? '★' : (SUIT_SYMBOLS[c.suit] || '✦')));
    art.appendChild(el('span', 'number', numberLabel(c)));
    front.appendChild(art);
    inner.appendChild(front);
    card.appendChild(inner);
    slot.appendChild(card);

    var info = el('div', 'info');
    info.appendChild(el('div', 'card-name', c.nameKo));
    info.appendChild(el('div', c.reversed ? 'card-dir reversed' : 'card-dir', dir));
    info.appendChild(el('div', 'card-kw', (c.keywords || []).join(' · ')));
    slot.appendChild(info);

    els.cards.appendChild(slot);
    return card;
  }

  function renderCards(cards) {
    els.cards.textContent = '';
    return cards.map(buildCard);
  }

  /** 카드를 차례로 뒤집고, 모두 뒤집히면 onDone 을 부른다. */
  function flipAll(cardEls, startMs, gapMs, onDone) {
    cardEls.forEach(function (node, i) {
      later(function () { node.classList.add('flipped'); }, startMs + i * gapMs);
    });
    later(onDone, startMs + Math.max(cardEls.length - 1, 0) * gapMs + FLIP_DURATION_MS);
  }

  // ---------- 타이핑 효과 ----------

  var typer = { queue: '', timer: null, enabled: false };

  function typerReset() {
    if (typer.timer) { clearInterval(typer.timer); }
    typer.queue = '';
    typer.timer = null;
    typer.enabled = false;
  }

  function typerPush(text) {
    typer.queue += text;
    typerPump();
  }

  function typerPump() {
    if (!typer.enabled || typer.timer || !typer.queue) { return; }
    els.interpretation.classList.add('typing');
    typer.timer = setInterval(typerTick, TYPE_INTERVAL_MS);
  }

  function typerTick() {
    if (!typer.queue) {
      clearInterval(typer.timer);
      typer.timer = null;
      maybeFinish();
      return;
    }
    // 쌓인 글자가 많으면 한 번에 더 내보내서 스트림보다 뒤처지지 않게 한다.
    var n = typer.queue.length > 160 ? 4 : typer.queue.length > 60 ? 2 : 1;
    var code = typer.queue.charCodeAt(n - 1);
    if (code >= 0xD800 && code <= 0xDBFF) { n += 1; } // 서로게이트 쌍을 쪼개지 않는다
    els.interpretation.textContent += typer.queue.slice(0, n);
    typer.queue = typer.queue.slice(n);
  }

  // ---------- 리딩 진행 ----------

  function startReading() {
    if (busy || !selectedCategory) { return; }
    setBusy(true);
    showSetupMessage('');
    resetResult();

    var myRun = {
      token: null, fallback: false, done: false, errored: false,
      streamEnded: false, flipped: false, cardsShown: false, finished: false
    };
    run = myRun;
    show(els.setup, false);
    show(els.result, true);
    setNotice('카드를 섞고 있어요…');

    var question = els.question.value.trim();
    var payload = { category: selectedCategory };
    if (question) { payload.question = question; }

    controller = new AbortController();

    fetch(API, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
      body: JSON.stringify(payload),
      signal: controller.signal
    }).then(function (res) {
      if (!res.ok) {
        return res.json().catch(function () { return {}; }).then(function (body) {
          throw new UserFacingError(body.message || '잠시 후 다시 시도해 주세요.');
        });
      }
      return readEvents(res.body, function (ev) { handleEvent(myRun, ev); });
    }).then(function () {
      if (run !== myRun) { return; }
      myRun.streamEnded = true;
      if (!myRun.done && !myRun.errored) {
        // done 이나 error 없이 스트림이 끝났다면 연결이 중간에 끊긴 것이다.
        myRun.errored = true;
        if (!myRun.cardsShown) {
          backToSetup();
          showSetupMessage('연결이 끊겼어요. 다시 시도해 주세요.');
          return;
        }
        setNotice('연결이 끊겨서 해석을 끝까지 받지 못했어요. 다시 시도해 주세요.', true);
      }
      maybeFinish();
    }).catch(function (err) {
      if (run !== myRun || (err && err.name === 'AbortError')) { return; }
      var message = err instanceof UserFacingError ? err.message : '네트워크 연결을 확인하고 다시 시도해 주세요.';
      if (!myRun.cardsShown) {
        // 카드가 나오기 전의 실패는 처음 화면으로 돌려보낸다.
        backToSetup();
        showSetupMessage(message);
        return;
      }
      myRun.streamEnded = true;
      myRun.errored = true;
      setNotice(message, true);
      maybeFinish();
    });
  }

  function handleEvent(r, ev) {
    if (run !== r) { return; }
    var data = ev.data || {};
    switch (ev.name) {
      case 'reading':
        r.token = data.token;
        r.cardsShown = true;
        show(els.resultTitle, true);
        els.resultTitle.textContent = (CATEGORY_LABELS[data.category] || '') + ' · 세 장의 카드';
        setNotice('카드를 펼치고 있어요…');
        flipAll(renderCards(data.cards || []), FLIP_START_MS, FLIP_GAP_MS, function () {
          r.flipped = true;
          if (!r.errored) { setNotice(''); }
          typer.enabled = true;
          typerPump();
          maybeFinish();
        });
        break;
      case 'chunk':
        typerPush(data.text || '');
        break;
      case 'done':
        r.done = true;
        r.token = data.token || r.token;
        r.fallback = !!data.fallback;
        break;
      case 'error':
        r.errored = true;
        r.streamEnded = true;
        if (!r.cardsShown) {
          backToSetup();
          showSetupMessage(data.message || '해석을 만들지 못했어요. 잠시 후 다시 시도해 주세요.');
        } else {
          setNotice(data.message || '해석을 만들지 못했어요. 잠시 후 다시 시도해 주세요.', true);
        }
        break;
      default:
        break;
    }
  }

  /** 스트림이 끝났고, 카드가 모두 뒤집혔고, 타이핑이 끝났을 때 한 번 마무리한다. */
  function maybeFinish() {
    var r = run;
    if (!r || r.finished || !r.streamEnded || !r.cardsShown || !r.flipped || typer.queue) { return; }
    r.finished = true;
    els.interpretation.classList.remove('typing');
    setBusy(false);
    controller = null;
    show(els.again, true);

    if (r.done && !r.fallback && r.token) {
      show(els.copy, true);
      setNotice('결과 링크를 복사해 두면 24시간 동안 다시 볼 수 있어요.');
      history.replaceState(null, '', '/?r=' + encodeURIComponent(r.token));
    } else if (r.done && r.fallback) {
      setNotice('지금은 해석을 완성하지 못했어요. 이 결과는 저장되지 않았어요. 잠시 후 다시 뽑아 주세요.', true);
    }
    // 오류로 끝난 경우에는 이미 안내 문구가 표시되어 있다.
  }

  // ---------- SSE 파서 ----------

  function parseBlock(block) {
    var name = 'message';
    var dataLines = [];
    block.split('\n').forEach(function (line) {
      if (!line || line.charAt(0) === ':') { return; }
      var idx = line.indexOf(':');
      var field = idx === -1 ? line : line.slice(0, idx);
      var value = idx === -1 ? '' : line.slice(idx + 1);
      if (value.charAt(0) === ' ') { value = value.slice(1); }
      if (field === 'event') { name = value; }
      else if (field === 'data') { dataLines.push(value); }
    });
    if (!dataLines.length) { return null; }
    var data = null;
    try { data = JSON.parse(dataLines.join('\n')); } catch (e) { return null; }
    return { name: name, data: data };
  }

  function readEvents(body, onEvent) {
    var reader = body.getReader();
    var decoder = new TextDecoder('utf-8');
    var buffer = '';

    function drain(final) {
      buffer = buffer.replace(/\r\n/g, '\n');
      var idx;
      while ((idx = buffer.indexOf('\n\n')) !== -1) {
        var ev = parseBlock(buffer.slice(0, idx));
        buffer = buffer.slice(idx + 2);
        if (ev) { onEvent(ev); }
      }
      if (final && buffer.trim()) {
        var last = parseBlock(buffer);
        buffer = '';
        if (last) { onEvent(last); }
      }
    }

    function pump() {
      return reader.read().then(function (chunk) {
        if (chunk.done) {
          buffer += decoder.decode();
          drain(true);
          return;
        }
        buffer += decoder.decode(chunk.value, { stream: true });
        drain(false);
        return pump();
      });
    }
    return pump();
  }

  // ---------- 공유 링크 ----------

  function shareUrl(token) {
    return location.origin + '/?r=' + encodeURIComponent(token);
  }

  function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) {
      return navigator.clipboard.writeText(text);
    }
    return new Promise(function (resolve, reject) {
      var area = document.createElement('textarea');
      area.value = text;
      area.setAttribute('readonly', '');
      area.style.position = 'fixed';
      area.style.opacity = '0';
      document.body.appendChild(area);
      area.select();
      var ok = false;
      try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
      document.body.removeChild(area);
      if (ok) { resolve(); } else { reject(new Error('copy failed')); }
    });
  }

  function onCopyClick() {
    var token = (run && run.token) || sharedToken;
    if (!token) { return; }
    var url = shareUrl(token);
    copyText(url).then(function () {
      els.copy.textContent = '복사됐어요 ✓';
      setTimeout(function () { els.copy.textContent = '링크 복사'; }, 2000);
    }).catch(function () {
      setNotice('복사하지 못했어요. 이 링크를 직접 복사해 주세요: ' + url);
    });
  }

  function loadShared(token) {
    sharedToken = null;
    show(els.setup, false);
    show(els.result, true);
    setNotice('결과를 불러오고 있어요…');
    els.again.textContent = '나도 카드 뽑아보기';

    fetch(API + '/' + encodeURIComponent(token), { headers: { 'Accept': 'application/json' } })
      .then(function (res) {
        if (res.status === 404) {
          throw new UserFacingError('결과를 찾을 수 없어요. 링크가 잘못되었거나 보관 기간(24시간)이 지났을 수 있어요.');
        }
        if (!res.ok) { throw new UserFacingError('결과를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.'); }
        return res.json();
      })
      .then(function (data) {
        sharedToken = data.token;
        show(els.resultTitle, true);
        els.resultTitle.textContent = '공유된 결과 · ' + (CATEGORY_LABELS[data.category] || '') + ' · 세 장의 카드';
        setNotice('');
        flipAll(renderCards(data.cards || []), 300, 400, function () {});
        if (data.interpretation) {
          els.interpretation.textContent = data.interpretation;
        } else {
          setNotice('이 결과에는 저장된 해석이 없어요.');
        }
        show(els.copy, true);
        show(els.again, true);
      })
      .catch(function (err) {
        var message = err instanceof UserFacingError ? err.message : '네트워크 연결을 확인하고 다시 시도해 주세요.';
        setNotice(message, true);
        show(els.again, true);
      });
  }

  // ---------- 초기화 ----------

  categoryButtons.forEach(function (button) {
    button.addEventListener('click', function () {
      if (busy) { return; }
      selectedCategory = button.getAttribute('data-category');
      categoryButtons.forEach(function (b) {
        b.setAttribute('aria-pressed', String(b === button));
      });
      updateDrawButton();
      showSetupMessage('');
    });
  });

  els.question.addEventListener('input', function () {
    var length = els.question.value.length;
    els.count.textContent = String(length);
    els.count.parentNode.classList.toggle('full', length >= MAX_QUESTION);
  });

  els.draw.addEventListener('click', startReading);
  els.copy.addEventListener('click', onCopyClick);
  els.again.addEventListener('click', function () {
    sharedToken = null;
    els.again.textContent = '다시 뽑기';
    backToSetup();
  });

  // 페이지를 떠나면 연결을 끊어서 서버가 LLM 호출을 멈추게 한다.
  window.addEventListener('pagehide', function () { if (controller) { controller.abort(); } });

  var shared = new URLSearchParams(location.search).get('r');
  if (shared !== null) {
    if (TOKEN_PATTERN.test(shared)) {
      loadShared(shared);
    } else {
      show(els.setup, false);
      show(els.result, true);
      setNotice('올바르지 않은 링크예요.', true);
      els.again.textContent = '나도 카드 뽑아보기';
      show(els.again, true);
    }
  }
})();

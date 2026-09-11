// What the sheet does once it is drawn: the hover panels, and the portrait.
//
// The renderer runs first and leaves two things behind -- window.TIPS, the
// content of every hover panel, and window.CHARKEY, the character's name, which
// is what a dropped portrait is remembered against.
window.gdSheetReady = function () {
// The portrait: drop a screenshot on the panel and it stays, kept in this
// browser against the character's name. Drop several -- taken a rotation step
// apart in game -- and it turns. A picture baked in by the generator wins, so a
// shared sheet still carries its own.
(function () {
  const fig = document.getElementById('portrait');
  if (!fig) return;
  const KEY = 'gdsheet:portrait:' + JSON.stringify(window.CHARKEY);
  const input = fig.querySelector('input[type=file]');
  const clear = fig.querySelector('.pclear');
  const stack = fig.querySelector('.pstack');
  const cap = fig.querySelector('.pcap');
  const bar = fig.querySelector('.pbar');
  const baked = !!fig.querySelector('img');
  let frames = [], at = 0, timer = null;
  const still = matchMedia('(prefers-reduced-motion: reduce)').matches;
  // A whole second a step. The game turns a character at about this rate, and
  // swapping ten frames in two seconds reads as a flicker rather than a turn.
  const STEP = 1000;

  // Every frame becomes its own img, decoded once and then only shown or
  // hidden. Re-pointing a single img at each data URL made it hitch, because
  // the browser decodes on the swap.
  function build() {
    stack.textContent = '';
    frames.forEach(function (src, i) {
      const im = new Image();
      im.src = src; im.alt = 'Character screenshot';
      im.className = i === 0 ? 'on' : '';
      stack.appendChild(im);
    });
    at = 0;
    fig.classList.toggle('has', frames.length > 0);
    fig.classList.toggle('turnable', frames.length > 1);
    // the caption is for things worth saying -- reading a drop, or failing to
    // remember it -- not a standing count of frames
    cap.textContent = '';
  }
  function show(i) {
    const ims = stack.children;
    if (!ims.length) return;
    at = ((i % ims.length) + ims.length) % ims.length;
    for (let k = 0; k < ims.length; k++) ims[k].className = k === at ? 'on' : '';
  }
  function badge() { fig.classList.toggle('spinning', !!timer); }
  function play() {
    if (timer || frames.length < 2) return;
    timer = setInterval(function () {
      if (!document.hidden) show(at + 1);
    }, STEP);
    badge();
  }
  function stop() { clearInterval(timer); timer = null; badge(); }
  function step() { stop(); show(at + 1); }

  bar.addEventListener('click', function (ev) {
    const b = ev.target.closest('[data-act]');
    if (!b) return;
    ev.stopPropagation();
    ({play: play, stop: stop, step: step})[b.dataset.act]();
  });

  function keep() {
    try { localStorage.setItem(KEY, JSON.stringify(frames)); }
    catch (e) { cap.textContent = 'too large to remember \u2014 shown for now'; }
  }

  if (baked) {
    fig.classList.add('has', 'baked');
  } else {
    try {
      const saved = JSON.parse(localStorage.getItem(KEY) || 'null');
      if (saved) { frames = [].concat(saved); build(); if (!still) play(); }
    } catch (e) {}
  }

  // Screenshots taken by hand are never the same crop twice: across ten of
  // these the figure's centre wandered 16px and its height varied by 3%, which
  // reads as the character jittering as you turn it. Each frame is measured --
  // the panel behind is near-black, so what is drawn stands out -- and then
  // redrawn scaled and centred on that box, so the figure sits still and only
  // the pose changes.
  function contentBox(cv, ctx) {
    const d = ctx.getImageData(0, 0, cv.width, cv.height).data;
    let x0 = cv.width, y0 = cv.height, x1 = -1, y1 = -1;
    for (let y = 0; y < cv.height; y++) {
      for (let x = 0; x < cv.width; x++) {
        const i = (y * cv.width + x) * 4;
        if (d[i + 3] > 24 && d[i] + d[i + 1] + d[i + 2] > 120) {
          if (x < x0) x0 = x;
          if (x > x1) x1 = x;
          if (y < y0) y0 = y;
          if (y > y1) y1 = y;
        }
      }
    }
    return x1 < 0 ? {x: 0, y: 0, w: cv.width, h: cv.height}
                  : {x: x0, y: y0, w: x1 - x0 + 1, h: y1 - y0 + 1};
  }

  function take(files) {
    const list = [].slice.call(files || [])
      .filter(function (f) { return /^image\/(png|jpeg|webp)$/.test(f.type); })
      // in the order they were taken, so a rotation comes out as one
      .sort(function (a, b) {
        return a.name.localeCompare(b.name, undefined, {numeric: true});
      });
    if (!list.length) {
      fig.classList.add('bad');
      setTimeout(function () { fig.classList.remove('bad'); }, 1200);
      return;
    }
    stop();
    cap.textContent = 'reading ' + list.length + '\u2026';
    const loaded = new Array(list.length);
    let done = 0;
    list.forEach(function (file, i) {
      const fr = new FileReader();
      fr.onload = function () {
        const im = new Image();
        im.onload = function () {
          const c = document.createElement('canvas');
          c.width = im.width; c.height = im.height;
          const cx = c.getContext('2d', {willReadFrequently: true});
          cx.drawImage(im, 0, 0);
          let box;
          try { box = contentBox(c, cx); }
          catch (e) { box = {x: 0, y: 0, w: im.width, h: im.height}; }
          loaded[i] = {im: im, box: box};
          if (++done === list.length) finish(loaded);
        };
        im.onerror = function () {
          loaded[i] = null;
          if (++done === list.length) finish(loaded);
        };
        im.src = fr.result;
      };
      fr.readAsDataURL(file);
    });
  }

  function finish(loaded) {
    const good = loaded.filter(Boolean);
    if (!good.length) { cap.textContent = ''; return; }
    // one canvas for every frame, sized to the largest figure, with each scaled
    // so the figures match and centred so they sit on the same spot
    const maxH = Math.max.apply(null, good.map(function (g) { return g.box.h; }));
    const maxW = Math.max.apply(null, good.map(function (g) { return g.box.w * maxH / g.box.h; }));
    // fewer frames can afford more pixels; the budget is what the browser will
    // hold, a few megabytes for everything on this page
    const cap2 = good.length <= 2 ? 900 : good.length <= 6 ? 750 : 560;
    const k = Math.min(1, cap2 / Math.max(maxW, maxH));
    const W = Math.round(maxW * k), H = Math.round(maxH * k);
    frames = good.map(function (g) {
      const s = (maxH / g.box.h) * k;
      const cv = document.createElement('canvas');
      cv.width = W; cv.height = H;
      const cx = cv.getContext('2d');
      cx.drawImage(g.im,
                   g.box.x, g.box.y, g.box.w, g.box.h,
                   (W - g.box.w * s) / 2, (H - g.box.h * s) / 2,
                   g.box.w * s, g.box.h * s);
      return cv.toDataURL('image/jpeg', 0.86);
    });
    build(); keep();
    if (!still) play();
  }

  ['dragenter', 'dragover'].forEach(function (t) {
    fig.addEventListener(t, function (ev) { ev.preventDefault(); fig.classList.add('over'); });
  });
  ['dragleave', 'dragend'].forEach(function (t) {
    fig.addEventListener(t, function () { fig.classList.remove('over'); });
  });
  fig.addEventListener('drop', function (ev) {
    ev.preventDefault(); fig.classList.remove('over'); take(ev.dataTransfer.files);
  });
  input.addEventListener('change', function () { take(input.files); });
  clear.addEventListener('click', function (ev) {
    ev.stopPropagation();
    stop(); frames = []; stack.textContent = ''; cap.textContent = '';
    fig.classList.remove('has', 'turnable');
    try { localStorage.removeItem(KEY); } catch (e) {}
  });

  // drag across the panel to turn by hand; a click that did not drag opens the
  // picker
  let down = false, startX = 0, startAt = 0, moved = false;
  fig.addEventListener('mousedown', function (ev) {
    if (ev.target.closest('.pbar') || ev.target === clear) return;
    stop();
    down = true; moved = false; startX = ev.clientX; startAt = at; ev.preventDefault();
  });
  addEventListener('mousemove', function (ev) {
    if (!down || frames.length < 2) return;
    const px = Math.max(14, fig.clientWidth / (frames.length * 1.4));
    const n = Math.round((ev.clientX - startX) / px);
    if (n) moved = true;
    show(startAt + n);
  });
  addEventListener('mouseup', function (ev) {
    if (down && !moved && !baked && !(ev.target.closest && ev.target.closest('.pbar')))
      input.click();
    down = false;
  });

  ['dragover', 'drop'].forEach(function (t) {
    addEventListener(t, function (ev) { if (!fig.contains(ev.target)) ev.preventDefault(); });
  });
})();


const TIPS = window.TIPS;
(function () {
  const tip = document.getElementById('tip');
  let shown = null;
  // follow the cursor, but flip before running off an edge rather than
  // letting the panel push the page wider
  function place(ev) {
    const pad = 14, w = tip.offsetWidth, h = tip.offsetHeight;
    let x = ev.clientX + pad, y = ev.clientY + pad;
    if (x + w > innerWidth - 8) x = ev.clientX - w - pad;
    if (y + h > innerHeight - 8) y = Math.max(8, innerHeight - h - 8);
    tip.style.left = x + 'px';
    tip.style.top = y + 'px';
  }
  // A large panel is read, not glanced at, so it anchors to what opened it and
  // stays put. Following the cursor is right for a small tooltip and unusable
  // for a list of seventeen buffs.
  function anchor(el) {
    const r = el.getBoundingClientRect(), pad = 10;
    const w = tip.offsetWidth, h = tip.offsetHeight;
    let x, y;
    // An item panel is far bigger than the icon that opens it, so anchoring it
    // to that icon buries the neighbouring ones and there is nothing left to
    // aim at. It goes beside the paper doll instead, clear of every cell, on
    // whichever side has room -- the side the hovered piece is on for
    // preference, since that is where the eye already is.
    const doll = tip.querySelector('.itip') ? el.closest('.doll') : null;
    const mid = doll && doll.querySelector('.dmid');
    if (mid) {
      // The gear sits in the two outer columns and the stat panels in the
      // middle, so the middle is the one place a panel can cover without
      // hiding an icon. It hugs whichever side of it the hovered piece is on.
      const m = mid.getBoundingClientRect();
      const near = (r.left + r.width / 2) < (m.left + m.width / 2);
      x = near ? m.left + pad : m.right - w - pad;
      x = Math.max(8, Math.min(x, innerWidth - w - 8));
      y = Math.max(8, Math.min(r.top, innerHeight - h - 8));
    } else {
      x = Math.min(r.left, innerWidth - w - 8);
      y = r.bottom + pad;
      if (y + h > innerHeight - 8) y = Math.max(8, r.top - h - pad);
    }
    tip.style.left = Math.max(8, x) + 'px';
    tip.style.top = y + 'px';
  }
  document.addEventListener('mouseover', function (ev) {
    const el = ev.target.closest('[data-tip]');
    if (!el) return;
    const html = TIPS[el.getAttribute('data-tip')];
    if (!html) return;
    shown = el;
    tip.innerHTML = html;
    tip.classList.toggle('wide', !!tip.querySelector('[data-wide]'));
    tip.querySelectorAll('img[data-from]').forEach(function (im) {
      const src = document.querySelector('[data-tip="' + im.dataset.from + '"] img');
      if (src) im.src = src.src;
    });
    tip.classList.remove('cols');
    const itip = tip.querySelector('.itip');
    if (itip) { itip.style.width = ''; itip.style.columnCount = ''; }
    tip.classList.add('on');
    // Measured, not assumed: only the panels that would run off the bottom of
    // the window are split into columns. The container has to be given the
    // width of those columns -- a multicol box does not grow to fit them, it
    // spills them outside itself, where they paint with no panel behind them.
    if (itip && tip.scrollHeight > innerHeight * 0.88) {
      const col = 380, gap = 30;
      let n = Math.min(3, Math.ceil(tip.scrollHeight / (innerHeight * 0.82)));
      while (n > 1 && col * n + gap * (n - 1) > innerWidth - 24) n--;
      if (n > 1) {
        itip.style.width = (col * n + gap * (n - 1)) + 'px';
        itip.style.columnCount = n;
        tip.classList.add('cols');
      }
    }
    if (tip.classList.contains('wide')) anchor(el); else place(ev);
  });
  document.addEventListener('mousemove', function (ev) {
    if (shown && !tip.classList.contains('wide')) place(ev);
  });
  document.addEventListener('mouseout', function (ev) {
    if (shown && !ev.relatedTarget?.closest?.('[data-tip]')) {
      shown = null;
      tip.classList.remove('on');
    }
  });
})();
};

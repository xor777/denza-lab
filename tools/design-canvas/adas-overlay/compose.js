/*
 * Lays a board out: a frame with its overlay, pairs of frames with their 1:1 pieces, the sheet of
 * states. Exposes window.composeBoard(canvas, id, repoUrl) -> Promise.
 */
(function (root) {
  'use strict';
  const { FRAMES, STATES, BOARDS, SIZE } = root.ADAS_BOARDS;
  const O = root.AdasOverlay;
  const images = {};

  function load(src, repo) {
    if (!images[src]) images[src] = new Promise((ok, fail) => {
      const im = new Image();
      im.onload = () => ok(im); im.onerror = () => fail(new Error('no frame ' + src));
      im.src = repo + '/' + src;
    });
    return images[src];
  }

  // one frame at its own pixels, with the overlay (or the probe's old one) drawn over it
  async function render(s, repo) {
    const f = FRAMES[s.frame];
    const im = f.src ? await load(f.src, repo) : null;
    const cv = document.createElement('canvas'); cv.width = f.w; cv.height = f.h;
    const c = cv.getContext('2d');
    if (!im) { c.fillStyle = '#000'; c.fillRect(0, 0, f.w, f.h); }
    else if (f.crop) c.drawImage(im, f.crop[0], f.crop[1], f.crop[2], f.crop[3], 0, 0, f.w, f.h);
    else c.drawImage(im, 0, 0, f.w, f.h);
    (f.pastes || []).forEach(p => c.drawImage(im, p.from[0], p.from[1], p.from[2], p.from[3], p.to[0], p.to[1], p.from[2], p.from[3]));
    if (s.old) O.oldOverlay(c, s.old);
    if (s.state) {
      const st = Object.assign({}, STATES[s.state], s.narrow ? { narrow: true } : {});
      O.draw(c, st, f[s.place || 'A']);
    }
    return cv;
  }

  function caption(c, str, x, y, px) {
    c.save();
    c.font = '500 ' + (px || 48) + 'px Roboto, sans-serif'; c.fillStyle = '#FFFFFF'; c.textBaseline = 'alphabetic';
    c.fillText(str, x, y);
    c.restore();
  }
  function frameEdge(c, x, y, w, h) {
    c.save(); c.strokeStyle = 'rgba(255,255,255,0.16)'; c.lineWidth = 2; c.strokeRect(x - 1, y - 1, w + 2, h + 2); c.restore();
  }

  async function composeBoard(canvas, id, repo) {
    const b = BOARDS[id];
    const [W, H] = SIZE[b.kind](b);
    canvas.width = W; canvas.height = H;
    canvas.style.width = W + 'px'; canvas.style.height = H + 'px';
    const c = canvas.getContext('2d');
    c.fillStyle = '#000'; c.fillRect(0, 0, W, H);
    if (b.kind === 'full') { c.drawImage(await render(b.shot, repo), 0, 0); return; }
    if (b.kind === 'pair') {
      const fw = 1248, fh = 780;
      for (let i = 0; i < 2; i++) {
        const x = 24 + i * (fw + 40), cv = await render(b.shots[i], repo);
        caption(c, b.captions[i], x, 72);
        c.drawImage(cv, x, 100, fw, fh); frameEdge(c, x, 100, fw, fh);
        if (b.crops) {
          const r = b.crops[i];
          c.drawImage(cv, r[0], r[1], r[2], r[3], x, 100 + fh + 24, r[2], r[3]); frameEdge(c, x, 100 + fh + 24, r[2], r[3]);
        }
      }
      return;
    }
    if (b.kind === 'crops') {
      for (let i = 0; i < 2; i++) {
        const x = 24 + i * (1248 + 40), cv = await render(b.shots[i], repo), r = b.crops[i];
        caption(c, b.captions[i], x, 72);
        c.drawImage(cv, r[0], r[1], r[2], r[3], x, 100, r[2], r[3]); frameEdge(c, x, 100, r[2], r[3]);
      }
      return;
    }
    if (b.kind === 'sheet') {
      const [tw, th] = b.tile;
      for (let i = 0; i < b.tiles.length; i++) {
        const [cap, s] = b.tiles[i];
        const x = 24 + (i % 3) * (tw + 24), y = 24 + Math.floor(i / 3) * (th + 84);
        const cv = await render(s, repo);
        const a = FRAMES[s.frame].A;
        const rx = Math.min(FRAMES[s.frame].w - tw, a.right + 24 - tw), ry = Math.min(FRAMES[s.frame].h - th, a.bottom + 22 - th);
        c.drawImage(cv, rx, ry, tw, th, x, y, tw, th); frameEdge(c, x, y, tw, th);
        caption(c, cap, x, y + th + 58, 44);
      }
    }
  }

  root.composeBoard = composeBoard;
})(window);

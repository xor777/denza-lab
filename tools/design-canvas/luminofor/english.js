/*
 * The boards' words in English, for `shot.py --lang en`. Nothing here holds a word: the table comes
 * from `res/values/strings.xml` and `res/values-en/strings.xml`, joined by key (`english_table()` in
 * shot.py), so a correction to the English reaches the boards with the next render.
 *
 * A fixture's string is what the app prints, so it is either a string resource as it stands, or one
 * with its arguments in, or a few of those side by side as the code joins them. `translate` tries
 * them in that order and a decimal comma becomes the point `AppDecimal` writes in English. What has no
 * key comes back as it was and is remembered in `LUMINOFOR_EN.stays`: `shot.py --lang en --stays`.
 *
 * The scenes are translated before anything is drawn, because `wrap()` breaks a paragraph at its
 * spaces. The words `luminofor.js` writes inline reach `fillText` and `measureText` one at a time, so
 * those two translate as well, from the glass picture's own keys first, and a width and the text drawn
 * in it cannot disagree.
 */
(function (root) {
  'use strict';

  const EN = root.LUMINOFOR_EN;
  const CYRILLIC = /[Ѐ-ӿ]/;
  const FIGURE = /^[-+−]?[0-9]+,[0-9]+$/;
  const exact = new Map(EN.exact);
  const drawn = new Map(EN.drawn);
  const templates = EN.templates.map(t => Object.assign({}, t, { re: new RegExp('^' + t.re + '$', 's') }));
  const memo = new Map();
  const stays = new Set();

  const decimal = s => (FIGURE.test(s) ? s.replace(',', '.') : s);

  /** One piece as a resource: as it stands, or a template whose arguments are all known in turn. */
  function known(s) {
    if (exact.has(s)) return exact.get(s);
    if (FIGURE.test(s)) return decimal(s);
    for (const t of templates) {
      const m = t.re.exec(s);
      if (!m) continue;
      const args = m.slice(1).map(whole);
      if (args.includes(null)) continue;
      const text = t.forms ? (+m[1] === 1 ? t.forms.one : t.forms.other) : t.en;
      return text.replace(/%%|%(?:([0-9]+)\$)?[sd]/g, (mark, n) => (mark === '%%' ? '%' : args[+(n || 1) - 1]));
    }
    return null;
  }

  /** A piece with no Russian left in it, or null. */
  function whole(s) {
    if (!CYRILLIC.test(s)) return decimal(s);
    const out = known(s) ?? headed(s);
    if (out !== null) return out;
    if (!s.includes(' · ')) return null;
    const parts = s.split(' · ').map(whole);
    return parts.includes(null) ? null : parts.join(' · ');
  }

  /** The longest known head with a known rest after it, as the code joins them; all of it or nothing. */
  function headed(s) {
    const words = s.split(' ');
    for (let i = words.length - 1; i > 0; i--) {
      const head = words.slice(0, i).join(' ');
      const h = /^[-+−]?[0-9.,:]+$/.test(head) ? decimal(head) : known(head);
      if (h === null) continue;
      const tail = words.slice(i).join(' ');
      const t = whole(tail);
      if (t !== null) return h + ' ' + t;
    }
    return null;
  }

  /**
   * Pieces joined at « · » go one by one, so a word that is the car's own stays Russian between
   * English neighbours, as the app would print it. Anything else is whole or not at all.
   */
  function translate(s) {
    if (!CYRILLIC.test(s)) return decimal(s);
    if (memo.has(s)) return memo.get(s);
    let out = whole(s);
    if (out === null && s.includes(' · ')) out = s.split(' · ').map(translate).join(' · ');
    if (out === null) { stays.add(s); out = s; }
    memo.set(s, out);
    return out;
  }

  function walk(v) {
    if (typeof v === 'string') return translate(v);
    if (Array.isArray(v)) return v.map(walk);
    if (v && typeof v === 'object') {
      const out = {};
      Object.keys(v).forEach(k => { out[k] = walk(v[k]); });
      // The language row says which language is on: the board is in English, so English is.
      if (v.t === 'segmented' && v.labels.includes('English')) out.selected = v.labels.indexOf('English');
      return out;
    }
    return v;
  }

  /** Every board but the scanner's: `feature/diagnostics` is parked and its words have no keys. */
  function boards(all) {
    const out = {};
    Object.keys(all).forEach(id => { if (!id.startsWith('scanner')) out[id] = walk(all[id]); });
    return out;
  }

  EN.translate = translate;
  EN.boards = boards;
  EN.stays = stays;
  EN.install = function () {
    const proto = root.CanvasRenderingContext2D.prototype;
    const fillText = proto.fillText;
    const measureText = proto.measureText;
    const inline = s => drawn.get(String(s)) ?? translate(String(s));
    proto.fillText = function (s, x, y, w) {
      return w === undefined ? fillText.call(this, inline(s), x, y) : fillText.call(this, inline(s), x, y, w);
    };
    proto.measureText = function (s) { return measureText.call(this, inline(s)); };
  };
})(window);

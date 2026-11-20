/* Planner — مرحله ۹: مدیریت کارهای عقب‌افتاده
   این فایل بعد از اسکریپت اصلی planner.html اجرا می‌شود و فقط همین بخش را اضافه یا جایگزین می‌کند. */
(function () {
  if (window.__overdue) return;
  window.__overdue = 1;

  /* ---------- تنظیمات قابل تغییر ---------- */
  // امتیاز تکمیل دیرهنگام (بر حسب تعداد روز تأخیر). الان ثابت ۵۰٪ است.
  // برای کاهشی شدن: return Math.max(0.25, 1 - 0.15 * daysLate);
  var LATE_CREDIT = function (daysLate) { return 0.5; };
  // false: انتقال کار همیشه ممکن است. true: فقط وقتی «ویرایش روزهای گذشته» فعال است.
  var MOVE_NEEDS_EDIT = false;

  var OD = { v: 'list', id: '', k: '' }, OM = null;
  var PRN = ['کم‌اهمیت', 'متوسط', 'خیلی مهم'], TYN = { d: 'روزانه', w: 'هفتگی', m: 'ماهانه' };

  var st = document.createElement('style');
  st.textContent = '.odr{display:flex;justify-content:space-between;gap:10px;padding:9px 0;border-bottom:1px solid var(--ln);font-size:13px}.odr:last-of-type{border:0}.odr span{color:var(--mut)}.odnote{white-space:pre-wrap;line-height:1.9;font-size:13px;overflow-wrap:anywhere}';
  document.head.appendChild(st);

  function daysAgo(iso) { return Math.round((new Date(TD() + 'T12:00:00') - new Date(iso + 'T12:00:00')) / 864e5); }
  // فقط کار یک‌باره‌ی تاریخ‌دار قابل انتقال است (روزانه/عادت و تکرارشونده نه)
  function movable(t) { return t.type !== 'd' && !!t.date && !t.rep; }
  function canMove() { return MOVE_NEEDS_EDIT ? !!S.allowPastEdit : true; }

  /* ---------- امتیاز تکمیل دیرهنگام ---------- */
  complete = function (t, k, o) {
    var late = (isIso(k) && k < TD()) ? daysAgo(k) : 0;
    setRec(t, k, Object.assign({ d: 1, t: Date.now(), dt: TD(), late: late }, o || {}));
    save();
    render();
  };
  function score2() {
    var d = 0;
    Object.values(S.R).forEach(function (m) {
      Object.values(m).forEach(function (r) { if (r.d) d += r.late ? LATE_CREDIT(r.late) : 1; });
    });
    return Math.round((d - missed(90).length) * 10) / 10;
  }
  var _dv = daysView;
  daysView = function () {
    return _dv()
      .replace(/(<div class="sc"><small>امتیاز<\/small><b>)[^<]*(<\/b>)/, '$1' + fs(score2()) + '$2')
      .replace('▾</button>', '‹</button>');
  };
  var _setH = setH;
  setH = function () {
    return _setH()
      + '<h3>۶. مدیریت کارهای عقب‌افتاده</h3><div class="g card"><p class="note">با فعال‌کردن این گزینه می‌توانی کارهای روزهای گذشته را تکمیل یا ویرایش کنی.</p>'
      + '<button class="b ' + (S.allowPastEdit ? '' : 's') + '" data-a="odperm">' + (S.allowPastEdit ? 'ویرایش روزهای گذشته فعاله' : 'فعال‌کردن ویرایش روزهای گذشته') + '</button></div>';
  };
  A.odperm = function () { S.allowPastEdit = !S.allowPastEdit; save(); var p = $('spg'); if (p) p.innerHTML = setH(); };

  /* ---------- پنل مستقل ---------- */
  function odRow(m, can) {
    var t = m.t;
    return '<div class="t g" style="--pc:' + PC[t.pri] + '"><button class="cb" data-a="cb" data-t="' + t.id + '" data-k="' + m.k + '"' + (can ? '' : ' disabled') + '></button>'
      + '<div class="n" data-a="odet" data-t="' + t.id + '" data-k="' + m.k + '"><b>' + esc(t.title) + '</b><small>' + TYN[t.type]
      + (t.time ? ' · ' + fs(t.time) : '') + (movable(t) ? ' · قابل انتقال' : '') + '</small></div></div>';
  }
  function odList() {
    var ms = missed(60), by = {}, can = !!S.allowPastEdit;
    ms.forEach(function (m) { (by[m.D] = by[m.D] || []).push(m); });
    var ds = Object.keys(by).sort().reverse();
    return '<div class="grab"></div><h2>کارهای عقب‌افتاده</h2><p class="note" style="text-align:center">'
      + (can ? 'ویرایش روزهای گذشته فعاله؛ می‌تونی کارها رو تکمیل یا ویرایش کنی.' : 'کارها فقط قابل مشاهده‌ان. برای تکمیل یا ویرایش، تو تنظیمات «ویرایش روزهای گذشته» رو فعال کن.')
      + ' هیچ کاری خودکار حذف یا جابه‌جا نمی‌شه.</p>'
      + (ds.length
        ? ds.map(function (D) { return '<h3>' + jf(D, true) + ' · ' + fs(daysAgo(D)) + ' روز پیش</h3>' + by[D].map(function (m) { return odRow(m, can); }).join(''); }).join('')
        : '<div class="empty">کار عقب‌افتاده‌ای نداری.</div>')
      + '<div class="rw" style="margin-top:14px"><button class="b s" data-a="close">بستن</button></div>';
  }
  function odDet() {
    var t = task(OD.id), k = OD.k;
    if (!t || !missed(60).some(function (m) { return m.t.id === t.id && m.k === k; })) { OD.v = 'list'; return odList(); }
    var can = !!S.allowPastEdit, mv = movable(t);
    var r = function (a, b) { return '<div class="odr"><span>' + a + '</span><b>' + b + '</b></div>'; };
    var h = '<div class="grab"></div><div class="rw" style="justify-content:space-between;flex-wrap:nowrap"><button class="ib" data-a="odback" aria-label="بازگشت">' + ic('back', 18) + '</button><h2 style="margin:0">جزئیات کار</h2><span style="width:40px"></span></div>'
      + '<div class="g card" style="margin-top:12px;border-inline-start:4px solid ' + PC[t.pri] + '"><b style="font-size:16px">' + esc(t.title) + '</b>'
      + r('نوع', TYN[t.type]) + r('تاریخ اصلی', jf(k, true) + ' ' + fs(jp(k).y)) + r('تأخیر', fs(daysAgo(k)) + ' روز') + r('اولویت', PRN[t.pri])
      + (t.time ? r('ساعت', fs(t.time)) : '') + (t.est ? r(t.type === 'd' ? 'هدف زمانی' : 'مدت تخمینی', fm(t.est)) : '')
      + (t.note ? '<div class="fl">توضیحات</div><div class="odnote">' + esc(t.note) + '</div>' : '') + '</div>';
    h += '<div class="rw" style="margin-top:6px">';
    if (can) h += '<button class="b" data-a="odok">تکمیل کار</button><button class="b s" data-a="odedit">ویرایش</button>';
    if (mv && canMove()) h += '<button class="b s" data-a="odtoday">انتقال به امروز</button><button class="b s" data-a="odother">انتقال به تاریخ دیگر</button>';
    h += '</div>';
    if (!can) h += '<p class="note" style="margin-top:10px">برای تکمیل یا ویرایش، تو تنظیمات «ویرایش روزهای گذشته» رو فعال کن.</p><button class="b s" data-a="odset">رفتن به تنظیمات</button>';
    if (!mv) h += '<p class="note" style="margin-top:10px">این کار ' + (t.type === 'd' ? 'روزانه (عادت)' : 'تکرارشونده') + ' است و قابل انتقال نیست؛ فقط کارهای یک‌باره‌ی تاریخ‌دار منتقل می‌شوند.</p>';
    return h;
  }
  function odDraw() {
    var p = document.getElementById('odp');
    if (p) p.innerHTML = OD.v === 'det' ? odDet() : odList();
  }
  var _render = render;
  render = function () { _render(); odDraw(); };

  /* ---------- انتقال (فقط با اقدام خود کاربر) ---------- */
  function moveTask(id, D) {
    var t = task(id);
    if (!t || !movable(t)) return;
    t.date = D;
    save();
    toast('کار به ' + jf(D, true) + ' منتقل شد');
    render();
  }
  function omDraw() {
    var j = jp(OM.first), td = TD(), len = mlen(OM.first), g = '';
    for (var i = 0; i < dow(OM.first); i++) g += '<i></i>';
    for (var n = 1; n <= len; n++) {
      var D = addD(OM.first, n - 1);
      g += '<button class="dy' + (D === td ? ' td' : '') + '" data-a="omd" data-v="' + D + '"' + (D < td ? ' disabled' : '') + '>' + fs(n) + '</button>';
    }
    OM.el.innerHTML = '<div class="sh"><div class="grab"></div><h2>انتقال به تاریخ دیگر</h2><div class="hdr"><button class="ib" data-a="omn" data-v="-1">' + ic('back', 18)
      + '</button><b>' + MN[j.m - 1] + ' ' + fs(j.y) + '</b><button class="ib" data-a="omn" data-v="1" style="transform:scaleX(-1)">' + ic('back', 18)
      + '</button></div><div class="wk">' + WL.map(function (w) { return '<span>' + w + '</span>'; }).join('') + '</div><div class="dg">' + g
      + '</div><div class="rw" style="margin-top:14px"><button class="b s" data-a="close">بستن</button></div></div>';
  }

  /* ---------- رویدادها ---------- */
  A.sm = function () { OD = { v: 'list', id: '', k: '' }; layer('<div class="sh" id="odp"></div>'); odDraw(); };
  A.odet = function (e) { OD = { v: 'det', id: e.dataset.t, k: e.dataset.k }; odDraw(); };
  A.odback = function () { OD.v = 'list'; odDraw(); };
  A.odok = function () { A.cb({ dataset: { t: OD.id, k: OD.k } }); };
  A.odedit = function () { openForm(OD.id); };
  A.odset = function () { closeL(); A.settings(); };
  A.odtoday = function () {
    var id = OD.id;
    ask('انتقال به امروز', 'تاریخ این کار به امروز تغییر می‌کند و از عقب‌افتاده‌ها خارج می‌شود. ادامه بدم؟', 'انتقال', function () { moveTask(id, TD()); });
  };
  A.odother = function () { OM = { id: OD.id, first: mfirst(TD()), el: layer('') }; omDraw(); };
  A.omn = function (e) { OM.first = +e.dataset.v > 0 ? mfirst(addD(OM.first, 32)) : mfirst(addD(OM.first, -1)); omDraw(); };
  A.omd = function (e) { var id = OM.id, D = e.dataset.v; closeL(); moveTask(id, D); };

  render();
})();
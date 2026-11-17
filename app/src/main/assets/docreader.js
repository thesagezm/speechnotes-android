// docreader.js — the headless doc formats' reader glue (mobi/fb2/rtf/html/
// txt; speechnotes-linux-rust's ServedBookDoc shape). One chapter at a time
// is fetched from the /docs/<bookId>/ PathHandler into #content, the same
// readerAppearance JSON protocol reader.js speaks drives the look (the Rust
// scheme mechanism: theme background !important on the html element, body
// transparent — typography on #content when respectStyles is off), and
// scroll-fraction relocations ride the AndroidReaderBridge. No epub.js —
// there is no container to parse, the parser already reduced the file.
(function () {
  var params = new URLSearchParams(location.search);
  // RELATIVE path under the shell origin ("/docs/<bookId>/"): the chapters
  // and the images both resolve here.
  var bookPath = params.get("bookPath") || "/";
  var startChapter = parseInt(params.get("chapter") || "0", 10);
  var startFraction = parseFloat(params.get("fraction") || "0");

  var STATE = {
    theme: params.get("theme") || "light",
    fontSize: parseInt(params.get("fontSize") || "100", 10),
    font: params.get("font") || "book",
    lineHeight: parseFloat(params.get("lineHeight") || "1.55"),
    paraSpacing: parseFloat(params.get("paraSpacing") || "0"),
    letterSpacing: parseFloat(params.get("letterSpacing") || "0"),
    padding: parseInt(params.get("padding") || "16", 10),
    respectStyles: params.get("respectStyles") === "1",
    chapter: startChapter,
    // Where the next mount's scroll lands: 0 for every chapter JUMP, the
    // restored fraction only on the very first open.
    pendingFraction: startFraction,
    spineIndex: startChapter
  };

  // The four schemes the family shares — speechnotes-linux-rust's exact
  // values, the same set reader.js applies to EPUBs.
  var THEMES = {
    light: { background: "#ffffff", color: "#000000" },
    sepia: { background: "#f6f3e9", color: "#39322b" },
    dark: { background: "#1c1c1e", color: "#e6e4e2" },
    trueBlack: { background: "#000000", color: "#ffffff" }
  };

  var FONTS = {
    serif: 'Georgia, "Iowan Old Style", "Palatino", serif',
    sans: '-apple-system, "Helvetica Neue", Arial, sans-serif',
    mono: '"SF Mono", Menlo, Consolas, monospace'
  };

  function post(msg) {
    try { webkit.messageHandlers.reader.postMessage(msg); } catch (e) { /* no native side */ }
  }

  var content = document.getElementById("content");

  // The chapters' relative img srcs (img/NNNNNN) must resolve to the
  // PathHandler, not to the shell's /assets/ directory — a <base href> does
  // exactly that (the linux reader serves pages under the same prefix).
  function installBase() {
    if (document.querySelector("base")) return;
    var base = document.createElement("base");
    base.href = bookPath;
    document.head.appendChild(base);
  }

  // --- appearance -------------------------------------------------------
  // The Rust EpubView mechanism, applied to the main document (there is no
  // epub.js rendition to register rules with): the scheme's background lands
  // on the HTML element with !important and the BODY is forced transparent,
  // so nothing a chapter carries can defeat the theme; the foreground rides
  // the body. Typography applies only when the book's own styling is NOT
  // respected — the parsers emit plain XHTML, and "respect" here means the
  // browser's own rhythm stays.
  function applyAppearance() {
    if (!content) content = document.getElementById("content");
    var colors = THEMES[STATE.theme] || THEMES.light;
    document.documentElement.style.setProperty("background", colors.background, "important");
    document.body.style.background = "transparent";
    document.body.style.color = colors.color;
    if (content) {
      content.style.padding = STATE.padding + "px";
      if (!STATE.respectStyles) {
        if (STATE.fontSize !== 100) content.style.fontSize = STATE.fontSize + "%";
        else content.style.fontSize = "";
        if (FONTS[STATE.font]) content.style.fontFamily = FONTS[STATE.font];
        else content.style.fontFamily = "";
        content.style.lineHeight = STATE.lineHeight;
        content.style.letterSpacing = STATE.letterSpacing > 0 ? STATE.letterSpacing + "px" : "";
        // Paragraph rhythm: one injected rule, updated in place (the
        // parser's chapters carry bare <p>s).
        var sheet = document.getElementById("sn-doc-styles");
        if (!sheet) {
          sheet = document.createElement("style");
          sheet.id = "sn-doc-styles";
          document.head.appendChild(sheet);
        }
        sheet.textContent = STATE.paraSpacing > 0
          ? "#content p { margin: 0 0 " + STATE.paraSpacing + "em; }"
          : "";
      } else {
        content.style.fontSize = "";
        content.style.fontFamily = "";
        content.style.lineHeight = "";
        content.style.letterSpacing = "";
        var sheet = document.getElementById("sn-doc-styles");
        if (sheet) sheet.textContent = "";
      }
    }
  }

  // --- chapter mounting --------------------------------------------------

  // The served page is a standalone document (chapterPage: doctype, html,
  // head with a title, body). Mount the body's own HTML — innerHTML never
  // executes scripts, so nothing the chapter carries can run here.
  function mountChapter(html) {
    var doc = new DOMParser().parseFromString(html, "text/html");
    content.innerHTML = doc.body ? doc.body.innerHTML : html;
    applyAppearance();
    var target = STATE.pendingFraction;
    STATE.pendingFraction = 0;
    // Two frames: the innerHTML must lay out before scrollTop sticks.
    requestAnimationFrame(function () {
      requestAnimationFrame(function () {
        scrollToFraction(target);
        reportPosition();
      });
    });
  }

  function scrollToFraction(f) {
    var se = document.scrollingElement;
    if (!se) return;
    var max = se.scrollHeight - se.clientHeight;
    se.scrollTop = max <= 4 ? 0 : Math.max(0, Math.min(f, 1)) * max;
  }

  function scrollFraction() {
    var se = document.scrollingElement;
    if (!se) return 0;
    var max = se.scrollHeight - se.clientHeight;
    if (max <= 4) return 0;
    var f = se.scrollTop / max;
    return Math.min(Math.max(f, 0), 1);
  }

  // The cache the WebView handler reads can be cold for a moment (the
  // native side warms it on screen entry; the LRU can also evict while a
  // doc-format book narrates elsewhere) — a 404 retries a few times before
  // the error surfaces.
  function loadChapter(index, attempt) {
    STATE.chapter = index;
    STATE.spineIndex = index;
    fetch(bookPath + "chapter-" + index).then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.text();
    }).then(mountChapter).catch(function (err) {
      if ((attempt || 0) < 8) {
        setTimeout(function () { loadChapter(index, (attempt || 0) + 1); }, 400);
        return;
      }
      post({ type: "error", message: String(err) });
    });
  }

  // --- relocation reporting ----------------------------------------------
  // Scroll-fraction relocations, rAF-throttled (one report per frame at
  // most): the chapter index stays the spine position, the fraction is
  // where the user is inside the chapter. Persisted through the ordinary
  // book position (position + positionFraction).
  var reportPending = false;
  function reportPosition() {
    reportPending = false;
    post({ type: "relocated", index: STATE.chapter, fraction: scrollFraction() });
  }
  window.addEventListener("scroll", function () {
    if (reportPending) return;
    reportPending = true;
    requestAnimationFrame(reportPosition);
  }, { passive: true });

  // --- chrome tap ----------------------------------------------------------
  // No page-turn zones (scrolled flow is native scrolling): a tap on the
  // page toggles the bars, unless it hit a link or a text selection.
  document.addEventListener("click", function (event) {
    var node = event.target;
    while (node && node !== document.body) {
      if (node.tagName === "A") return;
      node = node.parentElement;
    }
    var sel = document.getSelection && document.getSelection();
    if (sel && !sel.isCollapsed && String(sel).length > 0) return;
    post({ type: "chromeTap" });
  }, false);

  // --- auto-scroll (scrolled flow) ---------------------------------------
  // The same teleprompter reader.js runs for EPUBs: rAF-driven scroll, and
  // a chapter's end advances to the next chapter and keeps rolling. The
  // dwell guard keeps tiny chapters from fast-forwarding out of control.
  var AUTO = { on: false, speed: 40, last: 0, raf: 0, lastAdvance: 0 };

  function advanceChapter() {
    var now = performance.now();
    if (now - AUTO.lastAdvance < 800) return;
    AUTO.lastAdvance = now;
    var next = STATE.spineIndex + 1;
    if (next >= (parseInt(params.get("total") || "0", 10) || 0)) {
      // Without a total the book's end is only known by the 404 — try one
      // chapter forward; a failed load stops the loop.
      next = STATE.spineIndex + 1;
    }
    AUTO.last = 0;
    STATE.pendingFraction = 0;
    loadChapter(next);
  }

  function autoScrollFrame(ts) {
    if (!AUTO.on) return;
    if (AUTO.last) {
      var dt = Math.min((ts - AUTO.last) / 1000, 0.1);
      var se = document.scrollingElement;
      if (se) {
        var max = se.scrollHeight - se.clientHeight;
        if (max <= 4) {
          advanceChapter();
        } else if (se.scrollTop >= max - 2) {
          advanceChapter();
        } else {
          se.scrollTop += AUTO.speed * dt;
        }
      }
    }
    AUTO.last = ts;
    AUTO.raf = requestAnimationFrame(autoScrollFrame);
  }

  function stopAutoScroll() {
    AUTO.on = false;
    if (AUTO.raf) cancelAnimationFrame(AUTO.raf);
    AUTO.raf = 0;
    AUTO.last = 0;
  }

  // --- native -> reader commands ------------------------------------------

  window.docGoChapter = function (index) {
    if (typeof index !== "number") return;
    STATE.pendingFraction = 0;
    loadChapter(index);
  };

  window.readerGoChapter = window.docGoChapter;

  window.readerTheme = function (mode) {
    if (mode) { STATE.theme = mode; applyAppearance(); }
  };

  // Live appearance push — the JSON body mirrors the ReaderAppearance
  // struct. Flow is ignored: the doc reader is scrolled-only (the recorded
  // divergence — linux paginates bookdocs via foliate-js).
  window.readerAppearance = function (json) {
    if (!json) return;
    var a = typeof json === "string" ? JSON.parse(json) : json;
    if (a.theme) STATE.theme = a.theme;
    if (typeof a.fontSize === "number") STATE.fontSize = a.fontSize;
    if (a.font) STATE.font = a.font;
    if (typeof a.lineHeight === "number") STATE.lineHeight = a.lineHeight;
    if (typeof a.paraSpacing === "number") STATE.paraSpacing = a.paraSpacing;
    if (typeof a.letterSpacing === "number") STATE.letterSpacing = a.letterSpacing;
    if (typeof a.margin === "number") STATE.padding = a.margin;
    if (typeof a.respectStyles === "boolean") STATE.respectStyles = a.respectStyles;
    applyAppearance();
  };

  window.readerAutoScroll = function (on, speed) {
    if (typeof speed === "number" && speed > 0) AUTO.speed = speed;
    if (on && !AUTO.on) {
      AUTO.on = true;
      AUTO.last = 0;
      AUTO.raf = requestAnimationFrame(autoScrollFrame);
    } else if (!on && AUTO.on) {
      stopAutoScroll();
    }
  };

  window.readerAutoScrollSpeed = function (speed) {
    if (typeof speed === "number" && speed > 0) AUTO.speed = speed;
  };

  window.docReload = function () {
    loadChapter(STATE.chapter);
  };

  // --- boot ----------------------------------------------------------------
  installBase();
  loadChapter(startChapter);
})();

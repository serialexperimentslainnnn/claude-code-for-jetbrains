(function () {
  'use strict';

  var cc = window.cc || (window.cc = {});
  var CC = window.CC || (window.CC = {});

  var THEME_MAP = {
    accentSoft: '--accent-soft',
    codeBg: '--code-bg',
    fontFamily: '--font',
    monoFamily: '--mono',
    fontSize: '--fs',
  };

  function camelToKebab(key) {
    return key.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase();
  }

  CC.applyTheme = function (vars) {
    if (!vars || typeof vars !== 'object') return;
    var root = document.documentElement;
    if (!root || !root.style) return;
    CC.__themeVars = CC.__themeVars || {};
    for (var key in vars) {
      if (!Object.prototype.hasOwnProperty.call(vars, key)) continue;
      if (key === 'vibe' || key === 'reducedMotion') continue;
      var val = vars[key];
      if (val === null || val === undefined) continue;
      var prop = THEME_MAP[key] || '--' + camelToKebab(key);
      CC.__themeVars[prop] = String(val);
      try {
        root.style.setProperty(prop, String(val));
      } catch (e) {}
    }
    if (Object.prototype.hasOwnProperty.call(vars, 'vibe')) setVibe(!!vars.vibe);
    if (Object.prototype.hasOwnProperty.call(vars, 'reducedMotion')) {
      setReducedMotion(!!vars.reducedMotion);
    }
  };

  function setReducedMotion(on) {
    var body = document.body;
    if (!body) return;
    body.classList.toggle('reduced-motion', !!on);
    CC.reducedMotion = !!on;
  }

  CC.diagnostics = function () {
    var probe = document.createElement('div');
    probe.className = 'tool loading';
    document.body.appendChild(probe);
    var computed = window.getComputedStyle(probe);
    var report = {
      reducedMotionMedia: !!(
        window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches
      ),
      bodyHasReducedClass: document.body.classList.contains('reduced-motion'),
      toolAnimationName: computed.animationName,
      toolAnimationDuration: computed.animationDuration,
      toolAnimationIterations: computed.animationIterationCount,
      toolBorderColor: computed.borderTopColor,
      supportsColorMix: !!(
        window.CSS &&
        CSS.supports &&
        CSS.supports('color', 'color-mix(in srgb, red 50%, blue)')
      ),
      infoVar: getComputedStyle(document.documentElement).getPropertyValue('--info').trim(),
      warningVar: getComputedStyle(document.documentElement).getPropertyValue('--warning').trim(),
      styleSheets: document.styleSheets.length,
      userAgent: navigator.userAgent,
    };
    probe.remove();
    CC.send({ type: 'diag', report: JSON.stringify(report) });
    return report;
  };

  var vibeOn = false;
  var vibeTimer = 0;
  var vibeHue = 0;

  function hsl(h, s, l) {
    return 'hsl(' + Math.round(((h % 360) + 360) % 360) + ',' + s + '%,' + l + '%)';
  }
  function hsla(h, s, l, a) {
    return 'hsla(' + Math.round(((h % 360) + 360) % 360) + ',' + s + '%,' + l + '%,' + a + ')';
  }

  function vibeStep() {
    if (!vibeOn) return;
    vibeHue = (vibeHue + 6) % 360;
    var s = document.documentElement.style;
    var h = vibeHue;
    s.setProperty('--accent', hsl(h, 90, 60));
    s.setProperty('--accent-soft', hsla(h, 90, 60, 0.2));
    s.setProperty('--text', hsl(h + 40, 85, 72));
    s.setProperty('--dim', hsl(h + 90, 60, 66));
    s.setProperty('--border', hsla(h + 140, 75, 58, 0.6));
    s.setProperty('--success', hsl(h + 200, 75, 62));
    s.setProperty('--warning', hsl(h + 260, 80, 64));
  }

  function setVibe(on) {
    if (on === vibeOn) return;
    vibeOn = on;
    var body = document.body;
    if (on) {
      if (body) body.classList.add('vibe');
      vibeStep();
      if (!vibeTimer) vibeTimer = window.setInterval(vibeStep, 30);
    } else {
      if (vibeTimer) {
        window.clearInterval(vibeTimer);
        vibeTimer = 0;
      }
      if (body) body.classList.remove('vibe');
      var root = document.documentElement;
      var v = CC.__themeVars || {};
      for (var p in v) {
        if (Object.prototype.hasOwnProperty.call(v, p)) {
          try {
            root.style.setProperty(p, v[p]);
          } catch (e) {}
        }
      }
    }
  }
  CC.isVibe = function () {
    return vibeOn;
  };

  CC.nyanSvg = function () {
    return (
      '<svg viewBox="0 0 24 24" fill="none" aria-hidden="true">' +
      '<rect x="1" y="9.2" width="8" height="1.45" fill="#FF5B5B"/>' +
      '<rect x="1" y="10.65" width="8" height="1.45" fill="#FFA63D"/>' +
      '<rect x="1" y="12.1" width="8" height="1.45" fill="#FFF03D"/>' +
      '<rect x="1" y="13.55" width="8" height="1.45" fill="#4DE06A"/>' +
      '<rect x="1" y="15.0" width="8" height="1.45" fill="#4DA6FF"/>' +
      '<rect x="1" y="16.45" width="8" height="1.45" fill="#B84DFF"/>' +
      '<rect x="8.3" y="9" width="8" height="8" rx="1.8" fill="#FF9CCB" stroke="#E86FB0" stroke-width="0.8"/>' +
      '<circle cx="10.6" cy="11.4" r="0.6" fill="#19E0E0"/><circle cx="13.2" cy="13" r="0.6" fill="#19E0E0"/>' +
      '<circle cx="11.4" cy="14.4" r="0.6" fill="#19E0E0"/>' +
      '<path d="M14.6 8.7 15.7 6.8 16.6 8.7Z" fill="#9AA0A6"/><path d="M18.6 8.7 19.5 6.8 20.6 8.7Z" fill="#9AA0A6"/>' +
      '<rect x="14.3" y="8.5" width="6.6" height="7" rx="2.2" fill="#B9BCC2" stroke="#8A8E96" stroke-width="0.8"/>' +
      '<circle cx="16.4" cy="11.4" r="0.7" fill="#1A1A1A"/><circle cx="19" cy="11.4" r="0.7" fill="#1A1A1A"/>' +
      '<circle cx="15.7" cy="13" r="0.8" fill="#FF8FB6"/><circle cx="19.7" cy="13" r="0.8" fill="#FF8FB6"/>' +
      '<path d="M17 12.9v0.7M16.2 13.6h1.6" stroke="#5A5E66" stroke-width="0.6" stroke-linecap="round"/>' +
      '<rect x="9.7" y="16.6" width="1.6" height="2" rx="0.7" fill="#B9BCC2"/>' +
      '<rect x="13.4" y="16.6" width="1.6" height="2" rx="0.7" fill="#B9BCC2"/></svg>'
    );
  };

  cc.theme = function (vars) {
    CC.applyTheme(vars);
  };
})();

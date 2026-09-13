(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const TX = (CC.transcript = CC.transcript || ({} as TranscriptNs));

  const conversationEl = TX.conversationEl;

  let followFlag = false;
  const NEAR_BOTTOM = 80;
  let scrollScheduled = false;

  const SMOOTH_SCROLL_MAX_PX = 400;

  function isNearBottom(): boolean {
    const c = conversationEl();
    if (!c) {
      return true;
    }
    const distance = c.scrollHeight - c.scrollTop - c.clientHeight;
    return distance <= NEAR_BOTTOM;
  }

  TX.stickToBottom = function (): boolean {
    return followFlag || isNearBottom();
  };

  function scheduleScroll(stick: boolean): void {
    if (!stick) {
      return;
    }
    if (scrollScheduled) {
      return;
    }
    scrollScheduled = true;
    const raf =
      window.requestAnimationFrame ||
      function (fn: () => void) {
        return setTimeout(fn, 16);
      };
    raf(function () {
      scrollScheduled = false;
      const c = conversationEl();
      if (!c) {
        return;
      }
      const distance = c.scrollHeight - c.scrollTop - c.clientHeight;
      const smooth = distance > 0 && distance < SMOOTH_SCROLL_MAX_PX && !CC.reducedMotion;
      if (typeof c.scrollTo === 'function') {
        c.scrollTo({ top: c.scrollHeight, behavior: smooth ? 'smooth' : 'instant' });
      } else {
        c.scrollTop = c.scrollHeight;
      }
    });
  }
  TX.scheduleScroll = scheduleScroll;

  function subscribe(): boolean {
    if (!CC.on) {
      return false;
    }
    CC.on('follow', function (b) {
      followFlag = !!b;
      if (followFlag) {
        scheduleScroll(true);
      }
    });
    return true;
  }

  if (!subscribe()) {
    let tries = 0;
    const iv = setInterval(function () {
      tries++;
      if (subscribe() || tries > 50) {
        clearInterval(iv);
      }
    }, 20);
  }
})();

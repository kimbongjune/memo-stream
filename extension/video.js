'use strict';

const VIDEO_MAX_HEIGHT = 720;
const VIDEO_CRF = 28;

const OFFSCREEN_IDLE_MS = 5 * 60 * 1000;
let offscreenIdleTimer = null;

const releaseOffscreenSoon = () => {
  clearTimeout(offscreenIdleTimer);
  offscreenIdleTimer = setTimeout(() => {
    chrome.runtime.sendMessage({ type: 'close-offscreen' }).catch(() => {});
  }, OFFSCREEN_IDLE_MS);
};

const ensureOffscreen = async () => {
  const result = await chrome.runtime.sendMessage({ type: 'ensure-offscreen' });
  if (!result || !result.ok) {
    throw new Error((result && result.error) || 'offscreen unavailable');
  }
};

const compressVideo = async (file, onProgress = () => {}) => {
  await ensureOffscreen();

  const listener = (message) => {
    if (message && message.type === 'video-progress') {
      onProgress(message.progress);
    }
  };
  chrome.runtime.onMessage.addListener(listener);

  const inputUrl = URL.createObjectURL(file);
  let outputUrl = null;
  try {
    const result = await chrome.runtime.sendMessage({
      target: 'offscreen-video',
      op: 'compress',
      url: inputUrl,
      maxHeight: VIDEO_MAX_HEIGHT,
      crf: VIDEO_CRF,
    });
    if (!result || !result.ok) {
      throw new Error((result && result.error) || 'compress failed');
    }
    outputUrl = result.url;

    if (result.size >= file.size) {
      return file;
    }
    const response = await fetch(outputUrl);
    const blob = await response.blob();
    const base = (file.name || 'video').replace(/\.[^.]+$/, '');
    return new File([blob], `${base}.mp4`, { type: 'video/mp4' });
  } finally {
    chrome.runtime.onMessage.removeListener(listener);
    releaseOffscreenSoon();
    URL.revokeObjectURL(inputUrl);
    if (outputUrl) {
      chrome.runtime
        .sendMessage({ target: 'offscreen-video', op: 'revoke', url: outputUrl })
        .catch(() => {});
    }
  }
};

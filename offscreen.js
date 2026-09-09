'use strict';

let ffmpegPromise = null;

const loadFFmpeg = () => {
  if (ffmpegPromise) {
    return ffmpegPromise;
  }
  ffmpegPromise = (async () => {
    const instance = new FFmpegWASM.FFmpeg();
    instance.on('log', ({ message }) => console.debug('[ffmpeg]', message));
    await instance.load({
      coreURL: chrome.runtime.getURL('lib/ffmpeg/ffmpeg-core.js'),
      wasmURL: chrome.runtime.getURL('lib/ffmpeg/ffmpeg-core.wasm'),
    });
    return instance;
  })().catch((err) => {
    ffmpegPromise = null;
    throw err;
  });
  return ffmpegPromise;
};

const report = (progress) => {
  chrome.runtime.sendMessage({ type: 'video-progress', progress }).catch(() => {});
};

const compress = async ({ url, maxHeight = 720, crf = 28 }) => {
  const instance = await loadFFmpeg();
  const response = await fetch(url);
  const bytes = new Uint8Array(await response.arrayBuffer());

  const onProgress = ({ progress }) => {
    report(Math.max(0, Math.min(100, Math.round(progress * 100))));
  };
  instance.on('progress', onProgress);

  try {
    await instance.writeFile('in', bytes);
    await instance.exec([
      '-i', 'in',
      '-vf', `scale='trunc(min(1,${maxHeight}/ih)*iw/2)*2':'trunc(min(${maxHeight},ih)/2)*2'`,
      '-c:v', 'libx264',
      '-preset', 'veryfast',
      '-crf', String(crf),
      '-c:a', 'aac',
      '-b:a', '96k',
      '-movflags', '+faststart',
      '-threads', '1',
      'out.mp4',
    ]);
    const out = await instance.readFile('out.mp4');
    const blob = new Blob([out], { type: 'video/mp4' });
    return { url: URL.createObjectURL(blob), size: blob.size };
  } finally {
    instance.off('progress', onProgress);
    instance.deleteFile('in').catch(() => {});
    instance.deleteFile('out.mp4').catch(() => {});
  }
};

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (!message || message.target !== 'offscreen-video') {
    return undefined;
  }
  if (message.op === 'revoke') {
    try {
      URL.revokeObjectURL(message.url);
    } catch (err) {
      console.debug('revoke failed', err);
    }
    sendResponse({ ok: true });
    return true;
  }
  if (message.op === 'compress') {
    compress(message)
      .then((result) => sendResponse({ ok: true, ...result }))
      .catch((err) => {
        console.error('compress failed', err);
        sendResponse({ ok: false, error: String((err && err.message) || err) });
      });
    return true;
  }
  return undefined;
});

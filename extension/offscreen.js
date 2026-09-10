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

let jobChain = Promise.resolve();

const runExclusive = (job) => {
  const next = jobChain.then(job, job);
  jobChain = next.then(
    () => {},
    () => {}
  );
  return next;
};

const compress = ({ url, maxHeight = 720, crf = 28 }) => runExclusive(async () => {
  const instance = await loadFFmpeg();
  const response = await fetch(url);
  const bytes = new Uint8Array(await response.arrayBuffer());

  const tag = crypto.randomUUID().slice(0, 8);
  const input = `in-${tag}`;
  const output = `out-${tag}.mp4`;

  const onProgress = ({ progress }) => {
    report(Math.max(0, Math.min(100, Math.round(progress * 100))));
  };
  instance.on('progress', onProgress);

  try {
    await instance.writeFile(input, bytes);
    await instance.exec([
      '-i', input,
      '-vf', `scale='trunc(min(1,${maxHeight}/ih)*iw/2)*2':'trunc(min(${maxHeight},ih)/2)*2'`,
      '-c:v', 'libx264',
      '-pix_fmt', 'yuv420p',
      '-preset', 'veryfast',
      '-crf', String(crf),
      '-c:a', 'aac',
      '-b:a', '96k',
      '-movflags', '+faststart',
      '-threads', '1',
      output,
    ]);
    const out = await instance.readFile(output);
    const blob = new Blob([out], { type: 'video/mp4' });
    return { url: URL.createObjectURL(blob), size: blob.size };
  } finally {
    instance.off('progress', onProgress);
    instance.deleteFile(input).catch(() => {});
    instance.deleteFile(output).catch(() => {});
  }
});

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

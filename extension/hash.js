'use strict';

const MAX_IMAGE_DIM = 1600;
const THUMB_DIM = 300;

const sha256Hex = async (buffer) => {
  const digest = await crypto.subtle.digest('SHA-256', buffer);
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('');
};

const findOrInsertBlob = async ({
  blob,
  thumb = null,
  mime,
  size,
  thumbSize = 0,
  width,
  height,
  name = '',
}) => {
  const buffer = await blob.arrayBuffer();
  const sha = await sha256Hex(buffer);

  const existing = await findBlobBySha256(sha);
  if (existing) {
    await db.blobs.update(existing.id, { refCount: (existing.refCount || 0) + 1 });
    return { id: existing.id, sha256: sha, reused: true };
  }

  const id = await db.blobs.add({
    sha256: sha,
    blob,
    thumb,
    mime,
    size,
    thumbSize,
    width,
    height,
    name,
    refCount: 1,
    createdAt: Date.now(),
  });
  return { id, sha256: sha, reused: false };
};

const canvasToBlob = (canvas, type, quality) =>
  new Promise((resolve) => {
    canvas.toBlob((blob) => resolve(blob), type, quality);
  });

const resizeToCanvas = (source, maxDim) => {
  const scale = Math.min(1, maxDim / Math.max(source.width, source.height));
  const width = Math.max(1, Math.round(source.width * scale));
  const height = Math.max(1, Math.round(source.height * scale));
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  canvas.getContext('2d').drawImage(source, 0, 0, width, height);
  return canvas;
};

const makeThumbnail = (source, maxDim = THUMB_DIM) =>
  canvasToBlob(resizeToCanvas(source, maxDim), 'image/webp', 0.7);

const gifFrameCount = (bytes) => {
  let frames = 0;
  for (let i = 0; i + 3 < bytes.length; i++) {
    if (bytes[i] === 0x21 && bytes[i + 1] === 0xf9 && bytes[i + 2] === 0x04) {
      frames++;
      if (frames > 1) {
        return frames;
      }
    }
  }
  return frames;
};

const isAnimatedImage = async (file) => {
  const type = file.type || '';
  if (type === 'image/gif') {
    return gifFrameCount(new Uint8Array(await file.arrayBuffer())) > 1;
  }
  if (type !== 'image/webp') {
    return false;
  }
  const head = new Uint8Array(await file.slice(0, 21).arrayBuffer());
  if (head.length < 21) {
    return false;
  }
  const chunk = String.fromCharCode(head[12], head[13], head[14], head[15]);
  return chunk === 'VP8X' && (head[20] & 0x02) !== 0;
};

const processImageFile = async (file) => {
  const bitmap = await createImageBitmap(file);
  try {
    const canvas = resizeToCanvas(bitmap, MAX_IMAGE_DIM);
    const blob = await canvasToBlob(canvas, 'image/webp', 0.8);
    const thumb = await makeThumbnail(canvas, THUMB_DIM);
    const width = canvas.width;
    const height = canvas.height;

    let name = '';
    if (file.name) {
      name = `${file.name.replace(/\.[^.]+$/, '')}.webp`;
    }

    const result = await findOrInsertBlob({
      blob,
      thumb,
      name,
      mime: blob.type || 'image/webp',
      size: blob.size,
      thumbSize: thumb ? thumb.size : 0,
      width,
      height,
    });
    return { ...result, width, height };
  } finally {
    bitmap.close();
  }
};

function processBinaryFile(file) {
  return findOrInsertBlob({
    blob: file,
    thumb: null,
    mime: file.type || 'application/octet-stream',
    size: file.size,
    width: 0,
    height: 0,
    name: file.name || '',
  });
}

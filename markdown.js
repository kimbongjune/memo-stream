'use strict';

const BLOB_URI_OK =
  /^(?:(?:(?:f|ht)tps?|mailto|tel|callto|sms|cid|xmpp|blob):|[^a-z]|[a-z+.\-]+(?:[^a-z+.\-:]|$))/i;

const attrEscape = (value) =>
  String(value)
    .replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');

class Markdown {
  constructor() {
    this.parser = null;
  }

  getParser() {
    if (!this.parser) {
      this.parser = new marked.Marked({
        gfm: true,
        breaks: true,
      });
    }
    return this.parser;
  }

  collectRefs(html) {
    const ids = new Set();
    const pattern = /(?:src|href)="blob:(\d+)"/g;
    let match = pattern.exec(html);
    while (match !== null) {
      ids.add(Number(match[1]));
      match = pattern.exec(html);
    }
    return ids;
  }

  static fileNameOf(blob, id) {
    if (blob.name) {
      return blob.name;
    }
    const mime = blob.mime || 'application/octet-stream';
    const subtype = (mime.split('/')[1] || 'bin').split('+')[0] || 'bin';
    return `memo-${id}.${subtype}`;
  }

  static mediaMarkup(mime, url, fileName) {
    let element = '';
    if (mime.startsWith('video/')) {
      element = `<video src="${url}" controls preload="metadata"></video>`;
    } else {
      element = `<img src="${url}" alt="">`;
    }
    const save = '저장';
    return (
      `<span class="media">${element}` +
      `<a class="media-save" href="${url}" download="${fileName}" title="${fileName}">${save}</a></span>`
    );
  }

  async render(content) {
    const rawHtml = this.getParser().parse(content || '');
    const objectUrls = [];
    let html = rawHtml;

    for (const id of this.collectRefs(rawHtml)) {
      const record = await getBlob(id);
      if (!record || !record.blob) {
        continue;
      }
      const url = URL.createObjectURL(record.blob);
      objectUrls.push(url);

      const mime = record.mime || 'application/octet-stream';
      const fileName = attrEscape(Markdown.fileNameOf(record, id));

      if (mime.startsWith('image/') || mime.startsWith('video/')) {
        const tag = new RegExp(`<img[^>]*src="blob:${id}"[^>]*>`, 'g');
        html = html.replace(tag, Markdown.mediaMarkup(mime, url, fileName));
      }

      html = html.split(`src="blob:${id}"`).join(`src="${url}"`);
      html = html.split(`href="blob:${id}"`).join(`href="${url}" download="${fileName}"`);
    }

    const clean = DOMPurify.sanitize(html, {
      ADD_ATTR: ['target', 'download', 'controls', 'preload'],
      ALLOWED_URI_REGEXP: BLOB_URI_OK,
    });
    return { html: clean, objectUrls };
  }

  highlight(root, query) {
    const needle = (query || '').trim();
    if (!needle) {
      return;
    }
    const lower = needle.toLowerCase();
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode(node) {
        if (!node.nodeValue || !node.nodeValue.trim()) {
          return NodeFilter.FILTER_REJECT;
        }
        const parent = node.parentNode;
        if (!parent) {
          return NodeFilter.FILTER_REJECT;
        }
        const tag = parent.tagName;
        if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'MARK') {
          return NodeFilter.FILTER_REJECT;
        }
        return NodeFilter.FILTER_ACCEPT;
      },
    });

    const textNodes = [];
    while (walker.nextNode()) {
      textNodes.push(walker.currentNode);
    }

    for (const node of textNodes) {
      const text = node.nodeValue;
      const lowerText = text.toLowerCase();
      if (!lowerText.includes(lower)) {
        continue;
      }
      const fragment = document.createDocumentFragment();
      let cursor = 0;
      let index = lowerText.indexOf(lower, cursor);
      while (index !== -1) {
        if (index > cursor) {
          fragment.appendChild(document.createTextNode(text.slice(cursor, index)));
        }
        const mark = document.createElement('mark');
        mark.textContent = text.slice(index, index + needle.length);
        fragment.appendChild(mark);
        cursor = index + needle.length;
        index = lowerText.indexOf(lower, cursor);
      }
      if (cursor < text.length) {
        fragment.appendChild(document.createTextNode(text.slice(cursor)));
      }
      node.parentNode.replaceChild(fragment, node);
    }
  }
}

const md = new Markdown();

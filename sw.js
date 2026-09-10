'use strict';

if (chrome.sidePanel && chrome.sidePanel.setPanelBehavior) {
  chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true }).catch(() => {});
}

chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({
      id: 'save-selection',
      title: '선택한 텍스트를 메모로 저장',
      contexts: ['selection'],
    });
    chrome.contextMenus.create({
      id: 'save-page',
      title: '현재 페이지를 메모로 저장',
      contexts: ['page'],
    });
  });
});

const deliverCapture = (message, tabId) => {
  message.at = Date.now();

  if (tabId != null) {
    chrome.sidePanel.open({ tabId }).catch((err) => {
      console.debug('side panel open failed', err);
    });
  }

  return (async () => {
    await chrome.storage.session.set({ pendingCapture: message });
    try {
      await chrome.runtime.sendMessage(message);
      setTimeout(async () => {
        const { pendingCapture } = await chrome.storage.session.get('pendingCapture');
        if (pendingCapture && pendingCapture.at === message.at) {
          await chrome.storage.session.remove('pendingCapture');
        }
      }, 1500);
    } catch (err) {
      console.debug('capture queued for panel startup', err);
    }
  })();
};

chrome.contextMenus.onClicked.addListener((info, tab) => {
  if (info.menuItemId === 'save-selection' && info.selectionText) {
    deliverCapture(
      {
        type: 'capture',
        kind: 'selection',
        text: info.selectionText,
        url: tab && tab.url,
        title: tab && tab.title,
      },
      tab && tab.id
    );
    return;
  }
  if (info.menuItemId === 'save-page' && tab) {
    deliverCapture({ type: 'capture', kind: 'page', url: tab.url, title: tab.title }, tab.id);
  }
});

chrome.commands.onCommand.addListener((command, tab) => {
  if (command !== 'capture-page' || !tab) {
    return;
  }
  deliverCapture({ type: 'capture', kind: 'page', url: tab.url, title: tab.title }, tab.id);
});

let offscreenPending = null;

const ensureOffscreen = async () => {
  const exists = await chrome.offscreen.hasDocument();
  if (exists) {
    return;
  }
  if (!offscreenPending) {
    offscreenPending = chrome.offscreen
      .createDocument({
        url: 'offscreen.html',
        reasons: ['WORKERS'],
        justification: '영상 압축을 화면과 분리해 실행합니다.',
      })
      .finally(() => {
        offscreenPending = null;
      });
  }
  await offscreenPending;
};

const closeOffscreen = async () => {
  const exists = await chrome.offscreen.hasDocument();
  if (exists) {
    await chrome.offscreen.closeDocument();
  }
};

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (!message || message.type !== 'ensure-offscreen') {
    return undefined;
  }
  ensureOffscreen()
    .then(() => sendResponse({ ok: true }))
    .catch((err) => sendResponse({ ok: false, error: String((err && err.message) || err) }));
  return true;
});

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (!message || message.type !== 'close-offscreen') {
    return undefined;
  }
  closeOffscreen()
    .then(() => sendResponse({ ok: true }))
    .catch((err) => sendResponse({ ok: false, error: String((err && err.message) || err) }));
  return true;
});

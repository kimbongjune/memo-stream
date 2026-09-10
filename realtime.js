'use strict';

const RT_TOPIC = 'realtime:memo-stream';
const RT_TABLES = ['folders', 'notes', 'purges'];
const RT_HEARTBEAT_MS = 30000;
const RT_BACKOFF_MS = [1000, 2000, 5000, 10000, 30000];

let rtSocket = null;
let rtHeartbeat = null;
let rtRetry = null;
let rtAttempt = 0;
let rtRef = 0;

const rtNextRef = () => String((rtRef += 1));

const rtSend = (message) => {
  if (rtSocket && rtSocket.readyState === WebSocket.OPEN) {
    rtSocket.send(JSON.stringify(message));
  }
};

const rtJoin = (conf) => {
  const ref = rtNextRef();
  rtSend({
    topic: RT_TOPIC,
    event: 'phx_join',
    ref,
    join_ref: ref,
    payload: {
      access_token: conf.key,
      config: {
        broadcast: { self: false },
        presence: { key: '' },
        postgres_changes: RT_TABLES.map((table) => ({ event: '*', schema: 'public', table })),
      },
    },
  });
};

const rtClearTimers = () => {
  clearInterval(rtHeartbeat);
  clearTimeout(rtRetry);
  rtHeartbeat = null;
  rtRetry = null;
};

const rtStop = () => {
  rtClearTimers();
  const socket = rtSocket;
  rtSocket = null;
  if (socket) {
    socket.close();
  }
};

const rtRetryLater = () => {
  const delay = RT_BACKOFF_MS[Math.min(rtAttempt, RT_BACKOFF_MS.length - 1)];
  rtAttempt += 1;
  clearTimeout(rtRetry);
  rtRetry = setTimeout(rtConnect, delay);
};

function rtConnect() {
  const conf = syncConf();
  if (!conf) {
    rtStop();
    return;
  }
  if (rtSocket) {
    return;
  }

  const base = conf.url.replace(/^http/, 'ws');
  const url = `${base}/realtime/v1/websocket?apikey=${encodeURIComponent(conf.key)}&vsn=1.0.0`;
  let socket = null;
  try {
    socket = new WebSocket(url);
  } catch (err) {
    console.debug('realtime connect failed', err);
    rtRetryLater();
    return;
  }
  rtSocket = socket;

  const drop = () => {
    if (rtSocket !== socket) {
      return;
    }
    rtSocket = null;
    clearInterval(rtHeartbeat);
    rtHeartbeat = null;
    rtRetryLater();
  };

  socket.addEventListener('open', () => {
    rtAttempt = 0;
    rtJoin(conf);
    clearInterval(rtHeartbeat);
    rtHeartbeat = setInterval(() => {
      rtSend({ topic: 'phoenix', event: 'heartbeat', payload: {}, ref: rtNextRef() });
    }, RT_HEARTBEAT_MS);
  });

  socket.addEventListener('message', (event) => {
    let message = null;
    try {
      message = JSON.parse(event.data);
    } catch (err) {
      return;
    }
    if (!message) {
      return;
    }
    if (message.event === 'postgres_changes') {
      syncSoon(300);
      return;
    }
    if (message.event !== 'phx_reply' || message.topic !== RT_TOPIC) {
      return;
    }
    const status = message.payload && message.payload.status;
    if (status === 'ok') {
      syncNow().catch(() => {});
      return;
    }
    console.warn('realtime join rejected', message.payload && message.payload.response);
  });

  socket.addEventListener('close', drop);
  socket.addEventListener('error', drop);
}

const rtAlive = () => !!rtSocket && rtSocket.readyState === WebSocket.OPEN;

import { createGatorSession } from "./protocol.js";

export class GatorWebSocketClient {
  #socket;
  #session;
  #authenticated = false;
  #seenMessageIds = new Set();
  #receiveQueue = Promise.resolve();
  #sendQueue = Promise.resolve();

  constructor(url, { onMessage = () => {}, onEvent = () => {}, onState = () => {} } = {}) {
    this.url = url;
    this.onMessage = onMessage;
    this.onEvent = onEvent;
    this.onState = onState;
  }

  connect(accessToken) {
    if (this.#socket) throw new Error("Client is already connected");
    return new Promise((resolve, reject) => {
      this.#socket = new WebSocket(this.url);
      this.#socket.onopen = () => this.onState("connected");
      this.#socket.onmessage = ({ data }) => {
        this.#receiveQueue = this.#receiveQueue.then(async () => {
          let message = JSON.parse(data);
          if (!this.#session) {
            if (message.type !== "askauth") throw new Error("Expected HPKE key offer");
            this.#session = await createGatorSession(message, {
              type: "authenticateme",
              message: accessToken,
            });
            this.#socket.send(this.#session.initialEnvelope);
            accessToken = undefined;
            return;
          }
          message = JSON.parse(await this.#session.open(data));
          if (message.type === "authsuccess") {
            this.#authenticated = true;
            this.onState("authenticated");
            resolve(message);
          } else if (message.type === "forcedclosure") {
            reject(new Error(message.estatusDesc || "Connection rejected"));
            this.close();
          } else if (message.v === 2 && message.op === "message") {
            if (typeof message.messageId !== "string" || !message.messageId) throw new Error("Invalid v2 message");
            const duplicate = this.#seenMessageIds.has(message.messageId);
            this.#seenMessageIds.add(message.messageId);
            if (this.#seenMessageIds.size > 1024) this.#seenMessageIds.delete(this.#seenMessageIds.values().next().value);
            await this.ack(message.messageId);
            if (!duplicate) this.onMessage(message);
          } else if (message.type === "event") {
            this.onEvent(message);
          } else {
            this.onMessage(message);
          }
        }).catch((error) => {
          reject(error);
          this.close();
        });
      };
      this.#socket.onerror = () => reject(new Error("WebSocket connection failed"));
      this.#socket.onclose = () => {
        this.#authenticated = false;
        this.#socket = undefined;
        this.#session = undefined;
        this.#seenMessageIds.clear();
        this.onState("closed");
      };
    });
  }

  send(message) {
    if (!this.#authenticated || !this.#session) return Promise.reject(new Error("Client is not authenticated"));
    const operation = this.#sendQueue.then(async () => {
      this.#socket.send(await this.#session.seal(JSON.stringify(message)));
    });
    this.#sendQueue = operation.catch(() => this.close(1002, "Cannot send encrypted message"));
    return operation;
  }

  publish(kind, ids, payload, clientMessageId = crypto.randomUUID()) {
    return this.send({ v: 2, op: "publish", clientMessageId, target: { kind, ids }, payload });
  }

  subscribe(topics) {
    return this.send({ v: 2, op: "subscribe", topics });
  }

  unsubscribe(topics) {
    return this.send({ v: 2, op: "unsubscribe", topics });
  }

  ack(messageId) {
    return this.send({ v: 2, op: "ack", messageId, status: "delivered" });
  }

  presence() {
    return this.send({ v: 2, op: "presence" });
  }

  close(code = 1000, reason = "") {
    this.#socket?.close(code, reason);
  }

  get authenticated() {
    return this.#authenticated;
  }
}

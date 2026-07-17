import { createGatorSession } from "./protocol.js";

export class GatorWebSocketClient {
  #socket;
  #session;
  #authenticated = false;
  #receiveQueue = Promise.resolve();
  #sendQueue = Promise.resolve();

  constructor(url, { onMessage = () => {}, onEvent = () => {}, onState = () => {} } = {}) {
    this.url = url;
    this.onMessage = onMessage;
    this.onEvent = onEvent;
    this.onState = onState;
  }

  connect(usuario, passphrase) {
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
              message: passphrase,
              data: { usuario },
            });
            this.#socket.send(this.#session.initialEnvelope);
            usuario = undefined;
            passphrase = undefined;
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

  close(code = 1000, reason = "") {
    this.#socket?.close(code, reason);
  }

  get authenticated() {
    return this.#authenticated;
  }
}

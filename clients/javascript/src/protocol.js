import { AeadId, CipherSuite, KdfId, KemId } from "hpke-js";

const textEncoder = new TextEncoder();
const textDecoder = new TextDecoder();
const info = textEncoder.encode("gator-websockets-v1");

const suite = new CipherSuite({
  kem: KemId.DhkemX25519HkdfSha256,
  kdf: KdfId.HkdfSha256,
  aead: AeadId.Aes256Gcm,
});

export async function createGatorSession(offer, authentication) {
  if (offer?.data?.version !== "1" || offer?.data?.suite !== "DHKEM_X25519_HKDF_SHA256_AES_256_GCM") {
    throw new Error("Unsupported Gator WebSocket encryption suite");
  }
  const keyId = offer.data.keyId;
  const publicKey = decode(offer.keyForAuth);
  if (typeof keyId !== "string" || !keyId || publicKey.length !== 32) throw new Error("Invalid HPKE key offer");
  const recipientPublicKey = await suite.importKey("raw", publicKey.buffer);
  const sender = await suite.createSenderContext({ recipientPublicKey, info });
  const initialAad = bytes(`gator-ws-v1|${keyId}|hpke|0`);
  const ciphertext = await sender.seal(bytes(JSON.stringify(authentication)), initialAad);
  const clientMaterial = new Uint8Array(await sender.export(bytes("gator-ws-v1/client-to-server"), 44));
  const serverMaterial = new Uint8Array(await sender.export(bytes("gator-ws-v1/server-to-client"), 44));
  const outbound = await CipherState.create(clientMaterial);
  const inbound = await CipherState.create(serverMaterial);

  return {
    initialEnvelope: JSON.stringify({
      version: 1,
      keyId,
      encapsulation: encode(new Uint8Array(sender.enc)),
      sequence: 0,
      ciphertext: encode(new Uint8Array(ciphertext)),
    }),
    seal: async (message) => JSON.stringify({
      version: 1,
      keyId,
      sequence: outbound.sequence,
      ciphertext: encode(await outbound.seal(bytes(`gator-ws-v1|${keyId}|client-to-server|${outbound.sequence}`), bytes(message))),
    }),
    open: async (encodedEnvelope) => {
      const envelope = JSON.parse(encodedEnvelope);
      validateEnvelope(envelope, keyId);
      return textDecoder.decode(await inbound.open(
        envelope.sequence,
        bytes(`gator-ws-v1|${keyId}|server-to-client|${envelope.sequence}`),
        decode(envelope.ciphertext),
      ));
    },
  };
}

export class CipherState {
  static async create(material) {
    if (material.length !== 44) throw new Error("Invalid session material");
    const key = await crypto.subtle.importKey("raw", material.slice(0, 32), "AES-GCM", false, ["encrypt", "decrypt"]);
    return new CipherState(key, material.slice(32));
  }

  constructor(key, baseNonce) {
    this.key = key;
    this.baseNonce = baseNonce;
    this.sequence = 0;
  }

  async seal(aad, plaintext) {
    this.#checkSequence(this.sequence);
    const ciphertext = await crypto.subtle.encrypt(
      { name: "AES-GCM", iv: nonce(this.baseNonce, this.sequence), additionalData: aad, tagLength: 128 },
      this.key,
      plaintext,
    );
    this.sequence++;
    return new Uint8Array(ciphertext);
  }

  async open(receivedSequence, aad, ciphertext) {
    this.#checkSequence(receivedSequence);
    const plaintext = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: nonce(this.baseNonce, this.sequence), additionalData: aad, tagLength: 128 },
      this.key,
      ciphertext,
    );
    this.sequence++;
    return new Uint8Array(plaintext);
  }

  #checkSequence(receivedSequence) {
    if (!Number.isSafeInteger(receivedSequence) || receivedSequence !== this.sequence) {
      throw new Error("Unexpected encrypted message sequence");
    }
  }
}

function validateEnvelope(envelope, keyId) {
  if (envelope?.version !== 1 || envelope.keyId !== keyId || envelope.encapsulation != null
      || !Number.isSafeInteger(envelope.sequence) || typeof envelope.ciphertext !== "string") {
    throw new Error("Invalid encrypted envelope");
  }
}

function nonce(baseNonce, sequence) {
  const value = baseNonce.slice();
  let counter = BigInt(sequence);
  for (let index = 0; index < 8; index++) {
    value[value.length - 1 - index] ^= Number(counter & 0xffn);
    counter >>= 8n;
  }
  return value;
}

function bytes(value) {
  return textEncoder.encode(value);
}

export function encode(value) {
  let binary = "";
  for (const byte of value) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}

export function decode(value) {
  if (typeof value !== "string") throw new Error("Invalid Base64URL value");
  const base64 = value.replaceAll("-", "+").replaceAll("_", "/").padEnd(Math.ceil(value.length / 4) * 4, "=");
  return Uint8Array.from(atob(base64), (character) => character.charCodeAt(0));
}

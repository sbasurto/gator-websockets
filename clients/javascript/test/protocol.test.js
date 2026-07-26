import assert from "node:assert/strict";
import test from "node:test";
import { AeadId, CipherSuite, KdfId, KemId } from "hpke-js";
import { CipherState, createGatorSession, encode } from "../src/protocol.js";

const bytes = (value) => new TextEncoder().encode(value);

test("HPKE authentication and bidirectional session match the server protocol", async () => {
  const suite = new CipherSuite({
    kem: KemId.DhkemX25519HkdfSha256,
    kdf: KdfId.HkdfSha256,
    aead: AeadId.Aes256Gcm,
  });
  const recipient = await suite.kem.generateKeyPair();
  const publicKey = new Uint8Array(await suite.kem.serializePublicKey(recipient.publicKey));
  const offer = {
    type: "askauth",
    keyForAuth: encode(publicKey),
    data: { version: "1", keyId: "test-key", suite: "DHKEM_X25519_HKDF_SHA256_AES_256_GCM" },
  };
  const authentication = { type: "authenticateme", message: "access-token" };
  const client = await createGatorSession(offer, authentication);
  const initial = JSON.parse(client.initialEnvelope);
  const recipientContext = await suite.createRecipientContext({
    recipientKey: recipient.privateKey,
    enc: Uint8Array.from(Buffer.from(initial.encapsulation, "base64url")),
    info: bytes("gator-websockets-v1"),
  });
  const opened = await recipientContext.open(
    Uint8Array.from(Buffer.from(initial.ciphertext, "base64url")),
    bytes("gator-ws-v1|test-key|hpke|0"),
  );
  assert.deepEqual(JSON.parse(new TextDecoder().decode(opened)), authentication);

  const clientMaterial = new Uint8Array(await recipientContext.export(bytes("gator-ws-v1/client-to-server"), 44));
  const serverMaterial = new Uint8Array(await recipientContext.export(bytes("gator-ws-v1/server-to-client"), 44));
  const serverInbound = await CipherState.create(clientMaterial);
  const requestEnvelope = JSON.parse(await client.seal(JSON.stringify({ type: "getuserlist" })));
  const request = await serverInbound.open(0, bytes("gator-ws-v1|test-key|client-to-server|0"),
    Uint8Array.from(Buffer.from(requestEnvelope.ciphertext, "base64url")));
  assert.equal(new TextDecoder().decode(request), '{"type":"getuserlist"}');

  const serverOutbound = await CipherState.create(serverMaterial);
  const responseCiphertext = await serverOutbound.seal(bytes("gator-ws-v1|test-key|server-to-client|0"), bytes('{"type":"userslist"}'));
  const response = await client.open(JSON.stringify({
    version: 1, keyId: "test-key", sequence: 0, ciphertext: encode(responseCiphertext),
  }));
  assert.equal(response, '{"type":"userslist"}');
  await assert.rejects(() => client.open(JSON.stringify({
    version: 1, keyId: "test-key", sequence: 0, ciphertext: encode(responseCiphertext),
  })), /sequence/);
});

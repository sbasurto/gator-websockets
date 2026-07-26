import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { spawnSync } from "node:child_process";
import { GatorWebSocketClient } from "../src/GatorWebSocketClient.js";

const accessToken = process.env.E2E_TOKEN;
const urls = process.env.E2E_URLS?.split(",") ?? ["ws://127.0.0.1:12481", "ws://127.0.0.1:12482"];
if (!accessToken) throw new Error("E2E_TOKEN is required");
if (urls.length !== 2) throw new Error("E2E_URLS must contain two comma-separated URLs");
const subject = JSON.parse(Buffer.from(accessToken.split(".")[1], "base64url")).sub;

function inbox(url) {
  const waiting = [];
  const queued = [];
  const client = new GatorWebSocketClient(url, {
    onMessage(message) {
      const index = waiting.findIndex(({ predicate }) => predicate(message));
      if (index < 0) queued.push(message);
      else waiting.splice(index, 1)[0].resolve(message);
    },
  });
  return {
    client,
    next(predicate) {
      const index = queued.findIndex(predicate);
      if (index >= 0) return Promise.resolve(queued.splice(index, 1)[0]);
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error("Timed out waiting for v2 response")), 5000);
        waiting.push({ predicate, resolve: (message) => { clearTimeout(timer); resolve(message); } });
      });
    },
  };
}

const first = inbox(urls[0]);
const second = inbox(urls[1]);
let recovered = false;
try {
  const [firstAuth, secondAuth] = await Promise.all([
    first.client.connect(accessToken), second.client.connect(accessToken),
  ]);
  assert.equal(firstAuth.data.userId, subject);
  assert.equal(secondAuth.data.userId, subject);
  await second.client.subscribe(["e2e/distributed"]);
  await second.next((message) => message.op === "subscribed");
  await first.client.publish("topic", ["e2e/distributed"], { type: "e2e.message", data: { ok: true } });
  const [accepted, delivered] = await Promise.all([
    first.next((message) => message.op === "accepted"),
    second.next((message) => message.op === "message"),
  ]);
  assert.equal(delivered.messageId, accepted.messageId);
  assert.equal(delivered.payload.data.ok, true);
  assert.equal((await second.next((message) => message.op === "acked")).messageId, delivered.messageId);

  await first.client.presence();
  const presence = await first.next((message) => message.op === "presence");
  assert.ok(presence.users.some((user) => user.userId === subject && user.connections >= 2));

  if (process.env.E2E_RECOVERY === "1") {
    for (const value of [subject, firstAuth.data.connectionId, firstAuth.data.serverId,
      firstAuth.data.tenantId, firstAuth.data.applicationId]) {
      if (!/^[A-Za-z0-9:_-]{1,128}$/.test(value)) throw new Error("Unsafe recovery test identifier");
    }
    const messageId = randomUUID();
    const clientMessageId = randomUUID();
    const envelope = JSON.stringify({
      v: 2, op: "message", messageId, clientMessageId,
      tenantId: firstAuth.data.tenantId, applicationId: firstAuth.data.applicationId,
      sender: { userId: subject }, target: { kind: "user", ids: [subject] },
      payload: { type: "e2e.recovery", data: { ok: true } }, createdAt: new Date().toISOString(),
    }).replaceAll("'", "''");
    remote("psql -X -q -h 127.0.0.1 -U w3apps -d db_wmssoft -v ON_ERROR_STOP=1", `
      insert into ws_message(message_id,client_message_id,tenant_id,application_id,sender_user_id,target_kind,envelope)
      values ('${messageId}','${clientMessageId}','${firstAuth.data.tenantId}','${firstAuth.data.applicationId}',
        '${subject}','user','${envelope}'::jsonb);
      insert into ws_delivery(message_id,target_user_id,connection_id,server_id,available_at)
      values ('${messageId}','${subject}','${firstAuth.data.connectionId}','${firstAuth.data.serverId}',
        clock_timestamp()+interval '10 seconds');
    `);
    remote("sudo systemctl stop gator-websockets@12381.service");
    try {
      await new Promise((resolve) => setTimeout(resolve, 35_000));
      const replacement = inbox(process.env.E2E_RECOVERY_URL ?? "wss://artemisa.soft-gator.com:12380/");
      await replacement.client.connect(accessToken);
      const deliveredAfterFailure = await replacement.next((message) => message.messageId === messageId);
      assert.equal(deliveredAfterFailure.payload.type, "e2e.recovery");
      assert.equal((await replacement.next((message) => message.op === "acked" && message.messageId === messageId)).messageId,
        messageId);
      replacement.client.close();
      recovered = true;
    } finally {
      remote("sudo systemctl start gator-websockets@12381.service");
    }
  }
  process.stdout.write(JSON.stringify({
    distributed: firstAuth.data.serverId !== secondAuth.data.serverId,
    delivered: true, acknowledged: true, presence: true, recovered,
  }) + "\n");
} finally {
  first.client.close();
  second.client.close();
}

function remote(command, input = undefined) {
  const result = spawnSync("ssh", ["artemisavpn", command], { input, encoding: "utf8" });
  if (result.status !== 0) throw new Error(`Remote recovery step failed: ${result.stderr.trim()}`);
}

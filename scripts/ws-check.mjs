// Live-update check for scripts/smoke.sh (Node >= 22: global WebSocket and fetch, no packages).
// Usage: node ws-check.mjs <baseUrl> <token> <marketId>
// Connects to <baseUrl>/ws with STOMP, subscribes to the user's wallet queue and the public markets topic,
// places a bet of 10 on TRUE, and waits for both pushes. Exit 0 = both arrived.
const [base, token, marketId] = process.argv.slice(2);
const fail = (msg) => { console.error(`FAIL: ${msg}`); process.exit(1); };
setTimeout(() => fail(`no live update within 20 s; still waiting for: ${[...waiting].join(", ")}`), 20000);

const frame = (command, headers = {}) =>
  `${command}\n${Object.entries(headers).map(([k, v]) => `${k}:${v}`).join("\n")}\n\n\0`;
const waiting = new Set(["/user/queue/wallet", "/topic/public.markets"]);
const ws = new WebSocket(base.replace(/^http/, "ws") + "/ws", ["v12.stomp"]);

ws.onerror = () => fail("WebSocket error");
ws.onopen = () => ws.send(frame("CONNECT", { "accept-version": "1.2", host: "localhost", Authorization: `Bearer ${token}` }));
ws.onmessage = async ({ data }) => {
  for (const raw of String(data).split("\0")) {
    const [head, body] = raw.replace(/^\n+/, "").split("\n\n");
    const [command, ...lines] = head.split("\n");
    if (command === "ERROR") fail(`STOMP error: ${raw}`);
    if (command === "CONNECTED") {
      [...waiting].forEach((d, i) => ws.send(frame("SUBSCRIBE", { id: `s${i}`, destination: d })));
      await new Promise((r) => setTimeout(r, 500)); // the broker registers the subscriptions
      await placeBet();
    }
    if (command === "MESSAGE") {
      const destination = lines.find((l) => l.startsWith("destination:"))?.slice(12);
      const msg = JSON.parse(body);
      const ours = destination === "/user/queue/wallet" ? msg.betId != null : msg.type === "bet.placed" && msg.marketId === marketId;
      if (ours && waiting.delete(destination)) console.log(`  ${destination}: ${msg.type}`);
      if (waiting.size === 0) { ws.close(); process.exit(0); }
    }
  }
};

async function placeBet() { // retries while the market has not reached the bet service yet (404)
  for (let i = 0; i < 20; i++) {
    const res = await fetch(`${base}/api/bets`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Idempotency-Key": crypto.randomUUID(), "Content-Type": "application/json" },
      body: JSON.stringify({ marketId, side: "TRUE", amount: 10 }),
    });
    if (res.status === 202) return;
    if (res.status !== 404) fail(`bet returned ${res.status}: ${await res.text()}`);
    await new Promise((r) => setTimeout(r, 1000));
  }
  fail(`market ${marketId} never reached the bet service`);
}

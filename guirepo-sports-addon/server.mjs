import http from "node:http";
import { handleRequest } from "./src/addon.mjs";

const PORT = Number(process.env.PORT || 7001);
const HOST = process.env.HOST || "0.0.0.0";

const server = http.createServer(async (req, res) => {
  try {
    const hostHeader = req.headers.host || `127.0.0.1:${PORT}`;
    const url = `http://${hostHeader}${req.url || "/"}`;

    let bodyBuf = undefined;
    if (req.method !== "GET" && req.method !== "HEAD") {
      const chunks = [];
      for await (const ch of req) chunks.push(ch);
      bodyBuf = Buffer.concat(chunks);
    }

    const webReq = new Request(url, {
      method: req.method,
      headers: req.headers,
      body: bodyBuf
    });

    const webRes = await handleRequest(webReq);
    const outHeaders = {};
    webRes.headers.forEach((v, k) => {
      outHeaders[k] = v;
    });
    res.writeHead(webRes.status, outHeaders);
    if (req.method === "HEAD") {
      res.end();
      return;
    }
    const buf = Buffer.from(await webRes.arrayBuffer());
    res.end(buf);
  } catch (err) {
    res.writeHead(500, { "Content-Type": "application/json; charset=utf-8" });
    res.end(JSON.stringify({ error: err?.message || "Internal error" }));
  }
});

server.listen(PORT, HOST, () => {
  console.log(`[GuiRepo Sports Addon] Running at http://127.0.0.1:${PORT}/manifest.json (configure: http://127.0.0.1:${PORT}/configure)`);
});

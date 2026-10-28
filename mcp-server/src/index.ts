import { WebStandardStreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/webStandardStreamableHttp.js";
import { createServer } from "./tools.ts";

/**
 * Cloudflare Workers で動く MCP サーバーの入口
 *
 * https://<あなたのWorker>.workers.dev/mcp を claude.ai の「コネクタ」に登録して使う
 */
export default {
  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === "/mcp") {
      // リクエストごとにサーバーを作る（状態を持たない方式。Workersではこれが安全）
      const server = createServer();
      const transport = new WebStandardStreamableHTTPServerTransport({
        sessionIdGenerator: undefined,
        enableJsonResponse: true,
      });
      await server.connect(transport);
      return transport.handleRequest(request);
    }

    if (url.pathname === "/") {
      return new Response("GoodCamera MCP サーバーは動いています。接続先は /mcp です。\n", {
        headers: { "content-type": "text/plain; charset=utf-8" },
      });
    }

    return new Response("Not found", { status: 404 });
  },
};

# M031 Coding MCP controlled adapter

Date: 2026-10-22. This is a new Coding adapter, separate from the existing Spring AI Java MCP workbench. No production deployment or paid model call was performed by this workstream.

## Frozen compatibility

- MCP protocol: **2026-07-28**, Streamable HTTP, explicitly pinned; no automatic legacy fallback.
- Runtime dependency: `@modelcontextprotocol/client@2.3.1`, `ajv@8.17.1`; existing `undici@8.10.2`.
- Reference test dependency: `@modelcontextprotocol/server@2.3.1`.
- Node test runtime: 24.19.0. Project minimum remains 22.19.0 because pi/undici require it.
- SDK packages were verified on npm. Official sources: [versioning](https://modelcontextprotocol.io/docs/2026-07-28/learn/versioning), [authorization](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization), [TypeScript SDK](https://github.com/modelcontextprotocol/typescript-sdk).

The modern protocol uses server/discover and request metadata. Installing SDK v2 alone does not select it: this adapter sets versionNegotiation mode.pin explicitly. Optional sampling, elicitation, resources, prompts, task extensions, legacy SSE and stdio are not exposed. Servers requiring those capabilities are unsupported. Existing Java MCP was not upgraded.

## Integration contract

`createMcpRuntime({identity,servers,authorize,reserve,settle,credentialProvider,emit,signal})` returns `discover(), definitions(), execute(name,args,callId,{signal}?), close()`.

Identity must be bound by the trusted backend and include accountId/projectId/sessionId/taskId/agentId. Each server is trusted configuration:

```json
{"id":"docs","accountId":"account","projectId":"project","url":"https://mcp.example/mcp","transport":"http","timeoutMs":15000,"auth":{"resource":"https://mcp.example/mcp","issuer":"https://id.example","scopes":["read"]}}
```

Do not expose configuration, credentialProvider or testLoopback to model arguments or untrusted HTTP bodies. Configured servers require exact account/project matches. No command/args/env/headers configuration is accepted.

Use discover() before session creation, append definitions as pi customTools, and route each execute through this runtime. Keep pi's default MCP loader disabled. Call close in the same finalization path as the agent. The integration owner supplies existing approval UI and the single shared BudgetLedger. Existing ToolRuntime's on-risk treatment of arbitrary unknown tool names is not sufficient.

authorize gets identity/toolName/definitionHash/argumentsHash/callId/signal. All external tools require an affirmative decision, including readOnlyHint tools. Definitions are re-fetched and compared before and after approval. reserve runs before tools/call; settle receives usage:null because MCP is not a trusted token/cost meter. Result-unknown calls remain UNKNOWN and are never replayed. Parent persistence must preserve task/request identifiers across process restarts; this adapter's replay set is per runtime instance.

MCP events contain status, callId, names, hashes and approvalId only, never raw arguments, remote errors, credentials or results. Results are bounded text marked untrusted. URLs in results are not followed. Schema annotations/descriptions are excluded from model tool declarations, but meaningful schema values and result text remain untrusted data; this is not a complete prompt-injection solution.

Schema support is a bounded JSON Schema 2020-12 subset through Ajv: object arguments, 32 KiB definition, depth 16, 128 tools/server, bounded pagination. References, remote IDs and regex patterns are rejected; unsupported schema fails closed. Stable tool names include a hash of the remote tool name to avoid normalized-name collisions.

## Transport and OAuth

HTTP uses an exact configured endpoint, HTTPS, DNS validation in the actual TCP lookup, public address checks including mapped IPv6, redirect rejection, 256 KiB streamed response limits and bounded wall time. SDK retry/reconnection is disabled; duplicate tools/call request IDs are refused at transport. Abort closes the active response, but cannot prove that a remote side effect stopped. Timeout/disconnection after dispatch therefore settles UNKNOWN.

testLoopback is an explicit trusted test option for numeric loopback addresses only; never enable it from user settings. There is no stdio/shell startup and no terminal-policy bypass.

`createMcpOAuthBroker` implements a **pre-registered public-client authorization-code PKCE S256 profile** for trusted backend use. Config pins resource, issuer, protected-resource metadata URL, authorization-server metadata URL, authorization/token endpoints, client ID, redirect URI and scopes. RFC 9728 resource metadata and RFC 8414/OIDC metadata are checked against those pins. The broker validates state, exact issuer rules, redirect endpoint, scope and token expiry; code exchange is one-shot, including unknown outcomes. Tokens bind account/project/server/resource/issuer; revoke removes them. The default in-memory store is not a durable encrypted token vault; integration must supply trusted storage if persistence is wanted. Refresh, dynamic client registration, automatic authority selection, confidential-client secrets and DPoP are unsupported. Expired tokens require a new explicit sign-in.

No credential or PKCE verifier is returned to the model. begin() returns an authorization URL to the trusted UI; complete() returns connected state. credentialProvider is consumed only by the outbound transport. Echoed bearer tokens are redacted from tool output.

## Validation

`node --test coding-agent/test/mcp*.test.mjs`: **17/17 passed** on Node 24.19.0 on 2026-10-22.

Tests use independent official SDK servers over local HTTP (not a mock copy of this client's wire format), plus a local OAuth reference endpoint. Coverage: current-version discovery/call; malicious description omission; schema rejection; approval even for readOnlyHint; definition change while awaiting approval; account and credential isolation; budget denial; bearer echo redaction; disconnect/no replay; timeout; cancellation; oversized response; redirects; public/DNS address policy; PKCE/resource/issuer/state binding; one-shot code; revocation.

The initial reference fixture incorrectly called the SDK handler object as a function; it was corrected to handler.fetch before the successful run. The tests are engineering compatibility/security evidence, not model quality or external-provider acceptance. Runtime entry, dependency lock, Java configuration/UI and final deployment are owned by integration.


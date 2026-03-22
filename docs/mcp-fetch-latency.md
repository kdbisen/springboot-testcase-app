# MCP / Gemini CLI fetch latency (operations)

Fetch is slow when the **Gemini CLI** subprocess runs the model plus Jira MCP tools (often minutes). The Spring app only waits for that process; timeouts are in `application.properties` (`gemini.cli.timeout`, `gemini.cli.timeout.fetch`).

## Application levers (this repo)

| Setting | Effect |
|------------------|--------|
| `story.cache.enabled=true` | Skips MCP for issue keys already in `.story_cache/`. |
| `story.fetch.parallelism=3` | Fetches multiple **uncached** keys concurrently (capped). Use `1` to serialize. |
| Slim `fetchStory` in `jira-prompts.json` | Fewer requested fields → fewer tokens and tool rounds (default template is minimal). |
| `gemini.jira.prompts.path` | Point to an alternate JSON file without rebuilding. |

Raising `gemini.cli.timeout` does **not** speed up a successful run; it only delays failure.

## Gemini CLI / environment (outside Java)

- **MCP auto-approve / non-interactive**: avoid blocking on human approval for tool calls.
- **Model selection**: use a faster or smaller model for fetch if your CLI profile supports it; reserve larger models for test generation if needed.
- **Network**: run the CLI where latency to Jira and to the model API is low; corporate proxies add time per tool round.
- **Retries**: `gemini.cli.retry.max-attempts` multiplies wall time on failures; keep low unless you see transient errors.

## Fastest issue content (optional)

If your org allows **Jira REST** credentials (`jira.base-url`, `jira.email`, `jira.api-token`), the app uses HTTP for fetch/search instead of MCP—typically much faster than the agent loop.

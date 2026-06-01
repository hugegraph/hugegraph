#!/usr/bin/env python3
"""Small DeepWiki MCP client for repository-scoped Q&A."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Optional


DEFAULT_ENDPOINT = "https://mcp.deepwiki.com/mcp"
SCRIPT_DIR = Path(__file__).resolve().parent
SKILL_DIR = SCRIPT_DIR.parent
REPOS_PATH = SKILL_DIR / "references" / "repos.json"
STOPWORDS = {
    "a",
    "an",
    "and",
    "apache",
    "are",
    "as",
    "for",
    "hugegraph",
    "how",
    "in",
    "is",
    "it",
    "of",
    "on",
    "or",
    "the",
    "to",
    "used",
    "what",
    "where",
    "which",
    "why",
}


class McpError(RuntimeError):
    pass


def load_repos() -> dict[str, dict[str, Any]]:
    with REPOS_PATH.open("r", encoding="utf-8") as file:
        return json.load(file)


def resolve_repo(alias_or_name: str) -> str:
    repos = load_repos()
    profile = repos.get(alias_or_name)
    if profile is None:
        known = ", ".join(sorted(repos))
        raise McpError(f"Unknown repository alias '{alias_or_name}'. Known aliases: {known}.")
    if not profile.get("enabled", False):
        raise McpError(
            f"Repository alias '{alias_or_name}' is reserved but not enabled yet "
            f"({profile.get('repoName')})."
        )
    return str(profile["repoName"])


def cache_root() -> Path:
    configured = os.environ.get("DEEPWIKI_MCP_CACHE_DIR")
    if configured:
        return Path(configured).expanduser()
    xdg_cache = os.environ.get("XDG_CACHE_HOME")
    if xdg_cache:
        return Path(xdg_cache).expanduser() / "deepwiki-mcp"
    return Path.home() / ".cache" / "deepwiki-mcp"


def repo_cache_dir(repo_name: str) -> Path:
    return cache_root() / repo_name.replace("/", "__")


def contents_cache_path(repo_name: str) -> Path:
    return repo_cache_dir(repo_name) / "wiki-contents.md"


def write_text_atomic(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = path.with_suffix(path.suffix + ".tmp")
    tmp_path.write_text(text, encoding="utf-8")
    tmp_path.replace(path)


def parse_json(data: str) -> dict[str, Any]:
    try:
        parsed = json.loads(data)
    except json.JSONDecodeError as exc:
        raise McpError(f"DeepWiki MCP returned non-JSON content: {data[:500]}") from exc
    if not isinstance(parsed, dict):
        raise McpError(f"DeepWiki MCP returned an unexpected JSON payload: {data[:500]}")
    return parsed


def read_sse_response(response: Any, expected_id: Optional[int]) -> dict[str, Any]:
    data_lines: list[str] = []
    seen_payloads: list[str] = []
    max_seconds = float(os.environ.get("DEEPWIKI_MCP_STREAM_TIMEOUT", "120"))
    deadline = time.monotonic() + max_seconds

    while True:
        if time.monotonic() > deadline:
            break
        raw_line = response.readline()
        if not raw_line:
            break

        line = raw_line.decode("utf-8", errors="replace").rstrip("\r\n")
        if line.startswith("data:"):
            data_lines.append(line[5:].lstrip())
            continue
        if line:
            continue

        if not data_lines:
            continue

        data = "\n".join(data_lines)
        data_lines = []
        seen_payloads.append(data)
        parsed = parse_json(data)
        if expected_id is None or parsed.get("id") == expected_id:
            return parsed

    if data_lines:
        data = "\n".join(data_lines)
        seen_payloads.append(data)
        parsed = parse_json(data)
        if expected_id is None or parsed.get("id") == expected_id:
            return parsed

    preview = "\n".join(seen_payloads[-3:])
    raise McpError(
        f"DeepWiki MCP stream ended without response id {expected_id} "
        f"within {max_seconds:.0f}s: {preview[:500]}"
    )


class McpClient:
    def __init__(self, endpoint: str, protocol_version: str) -> None:
        self.endpoint = endpoint
        self.protocol_version = protocol_version
        self.session_id: Optional[str] = None
        self.next_id = 1

    def request(self, payload: dict[str, Any], expect_response: bool = True) -> Optional[dict[str, Any]]:
        body = json.dumps(payload).encode("utf-8")
        headers = {
            "Accept": "application/json, text/event-stream",
            "Content-Type": "application/json",
            "Mcp-Protocol-Version": self.protocol_version,
            "User-Agent": "hugegraph-deepwiki-skill/0.1.4",
        }
        if self.session_id:
            headers["Mcp-Session-Id"] = self.session_id

        req = urllib.request.Request(self.endpoint, data=body, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=90) as response:
                session_id = response.headers.get("Mcp-Session-Id")
                if session_id:
                    self.session_id = session_id
                if not expect_response:
                    return None
                content_type = response.headers.get("Content-Type", "")
                if "text/event-stream" in content_type:
                    parsed = read_sse_response(response, payload.get("id"))
                else:
                    text = response.read().decode("utf-8")
                    if not text.strip():
                        raise McpError("DeepWiki MCP returned an empty response.")
                    parsed = parse_json(text)
        except urllib.error.HTTPError as exc:
            details = exc.read().decode("utf-8", errors="replace")
            raise McpError(f"DeepWiki MCP HTTP {exc.code}: {details}") from exc
        except urllib.error.URLError as exc:
            raise McpError(f"Could not reach DeepWiki MCP endpoint: {exc.reason}") from exc

        if "error" in parsed:
            raise McpError(f"DeepWiki MCP error: {json.dumps(parsed['error'], ensure_ascii=False)}")
        return parsed

    def rpc(self, method: str, params: Optional[dict[str, Any]] = None) -> dict[str, Any]:
        payload: dict[str, Any] = {"jsonrpc": "2.0", "id": self.next_id, "method": method}
        self.next_id += 1
        if params is not None:
            payload["params"] = params
        result = self.request(payload)
        if result is None:
            raise McpError(f"DeepWiki MCP returned no response for {method}.")
        return result

    def notify(self, method: str, params: Optional[dict[str, Any]] = None) -> None:
        payload: dict[str, Any] = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            payload["params"] = params
        self.request(payload, expect_response=False)

    def initialize(self) -> None:
        self.rpc(
            "initialize",
            {
                "protocolVersion": self.protocol_version,
                "capabilities": {},
                "clientInfo": {"name": "hugegraph-deepwiki-skill", "version": "0.1.4"},
            },
        )
        self.notify("notifications/initialized", {})

    def call_tool(self, name: str, arguments: dict[str, Any]) -> Any:
        response = self.rpc("tools/call", {"name": name, "arguments": arguments})
        return response.get("result")


def extract_text(result: Any) -> str:
    if isinstance(result, dict):
        content = result.get("content")
        if isinstance(content, list):
            chunks: list[str] = []
            for item in content:
                if isinstance(item, dict):
                    text = item.get("text")
                    if isinstance(text, str):
                        chunks.append(text)
                    elif item.get("type") == "json":
                        chunks.append(json.dumps(item, ensure_ascii=False, indent=2))
            if chunks:
                return "\n\n".join(chunks)
        if "structuredContent" in result:
            return json.dumps(result["structuredContent"], ensure_ascii=False, indent=2)
    return json.dumps(result, ensure_ascii=False, indent=2)


def output_tool_result(client: McpClient, tool: str, arguments: dict[str, Any]) -> None:
    client.initialize()
    result = client.call_tool(tool, arguments)
    print(extract_text(result))


def read_wiki_contents(client: McpClient, repo_name: str) -> str:
    client.initialize()
    result = client.call_tool("read_wiki_contents", {"repoName": repo_name})
    return extract_text(result)


def ensure_cached_contents(client: McpClient, repo_name: str, refresh: bool = False) -> tuple[str, Path, bool]:
    path = contents_cache_path(repo_name)
    if path.exists() and not refresh:
        return path.read_text(encoding="utf-8"), path, False

    text = read_wiki_contents(client, repo_name)
    write_text_atomic(path, text)
    return text, path, True


def query_terms(query: str) -> list[str]:
    raw_terms = re.findall(r"[\w./:-]+|[\u4e00-\u9fff]+", query.lower())
    terms: list[str] = []
    for term in raw_terms:
        normalized = term.strip("._/:;-")
        if len(normalized) < 2 or normalized in STOPWORDS:
            continue
        if normalized not in terms:
            terms.append(normalized)
    return terms


def score_window(text: str, terms: list[str]) -> int:
    lowered = text.lower()
    score = 0
    for term in terms:
        pattern = rf"(?<![a-z0-9_]){re.escape(term)}(?![a-z0-9_])"
        count = len(re.findall(pattern, lowered))
        if count:
            score += count * max(1, min(len(term), 12))
    if "relevant source files" in lowered:
        score -= 40
    if lowered.count("src/main/") > 4 or lowered.count(".java") > 6:
        score -= 60
    return score


def search_cached_context(contents: str, query: str, limit: int) -> list[tuple[int, int, int, str]]:
    terms = query_terms(query)
    if not terms:
        return []

    lines = contents.splitlines()
    window_size = 30
    stride = 10
    candidates: list[tuple[int, int, int, str]] = []

    for start in range(0, len(lines), stride):
        end = min(len(lines), start + window_size)
        window = "\n".join(lines[start:end]).strip()
        if not window:
            continue
        score = score_window(window, terms)
        if score > 0:
            candidates.append((score, start + 1, end, window))

    candidates.sort(key=lambda item: item[0], reverse=True)
    selected: list[tuple[int, int, int, str]] = []
    selected_ranges: list[tuple[int, int]] = []
    for candidate in candidates:
        _, start, end, _ = candidate
        if any(start <= kept_end and end >= kept_start for kept_start, kept_end in selected_ranges):
            continue
        selected.append(candidate)
        selected_ranges.append((start, end))
        if len(selected) >= limit:
            break
    return selected


def output_context(client: McpClient, repo_name: str, query: str, limit: int, refresh: bool) -> None:
    contents, path, fetched = ensure_cached_contents(client, repo_name, refresh)
    matches = search_cached_context(contents, query, limit)

    print("# DeepWiki Cached Context")
    print(f"Repository: {repo_name}")
    print(f"Cache: {path}")
    print(f"Cache status: {'refreshed from DeepWiki' if fetched else 'reused local cache'}")
    print(f"Query: {query}")
    print()

    if not matches:
        print("No relevant cached DeepWiki wiki snippets were found for this query.")
        print("Fallback: use the `ask` command to request an online DeepWiki answer.")
        return

    for index, (score, start, end, snippet) in enumerate(matches, start=1):
        print(f"## Snippet {index} (score: {score}, lines: {start}-{end})")
        print("```text")
        print(snippet[:4000])
        print("```")
        print()


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Ask the official DeepWiki MCP server.")
    parser.add_argument(
        "--endpoint",
        default=os.environ.get("DEEPWIKI_MCP_ENDPOINT", DEFAULT_ENDPOINT),
        help=f"DeepWiki MCP endpoint. Defaults to {DEFAULT_ENDPOINT}.",
    )
    parser.add_argument(
        "--protocol-version",
        default=os.environ.get("DEEPWIKI_MCP_PROTOCOL_VERSION", "2025-06-18"),
        help="MCP protocol version to send during initialize.",
    )

    subparsers = parser.add_subparsers(dest="command", required=True)

    ask = subparsers.add_parser("ask", help="Ask a repository question.")
    ask.add_argument("--repo", default="hugegraph", help="Repository alias.")
    ask.add_argument("--question", required=True, help="Question to ask DeepWiki.")

    structure = subparsers.add_parser("structure", help="Read wiki structure.")
    structure.add_argument("--repo", default="hugegraph", help="Repository alias.")

    contents = subparsers.add_parser("contents", help="Read wiki contents.")
    contents.add_argument("--repo", default="hugegraph", help="Repository alias.")
    contents.add_argument("--refresh", action="store_true", help="Refresh the local DeepWiki contents cache.")

    context = subparsers.add_parser("context", help="Search cached DeepWiki wiki contents for a question.")
    context.add_argument("--repo", default="hugegraph", help="Repository alias.")
    context.add_argument("--query", required=True, help="Question or keywords to search in cached wiki contents.")
    context.add_argument("--limit", type=int, default=6, help="Maximum number of snippets to print.")
    context.add_argument("--refresh", action="store_true", help="Refresh the local DeepWiki contents cache before search.")

    tools = subparsers.add_parser("tools", help="List MCP tools for troubleshooting.")
    tools.set_defaults(command="tools")

    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    client = McpClient(args.endpoint, args.protocol_version)

    try:
        if args.command == "ask":
            repo_name = resolve_repo(args.repo)
            output_tool_result(
                client,
                "ask_question",
                {"repoName": repo_name, "question": args.question},
            )
        elif args.command == "structure":
            repo_name = resolve_repo(args.repo)
            output_tool_result(client, "read_wiki_structure", {"repoName": repo_name})
        elif args.command == "contents":
            repo_name = resolve_repo(args.repo)
            contents_text, _, _ = ensure_cached_contents(client, repo_name, args.refresh)
            print(contents_text)
        elif args.command == "context":
            repo_name = resolve_repo(args.repo)
            output_context(client, repo_name, args.query, args.limit, args.refresh)
        elif args.command == "tools":
            client.initialize()
            print(json.dumps(client.rpc("tools/list", {}).get("result"), ensure_ascii=False, indent=2))
        else:
            parser.error(f"Unhandled command {args.command}")
    except McpError as exc:
        print(f"deepwiki_mcp.py: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

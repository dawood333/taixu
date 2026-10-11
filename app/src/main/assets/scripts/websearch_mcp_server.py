#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# ==============================================================================
# TaiXu (LinuxAIRuntime) - 内置 Web 搜索与网页抓取 MCP 服务端 (stdio transport)
# ------------------------------------------------------------------------------
# 零依赖纯 Python 实现，开箱即用，无需 Node.js / npx 或外部 pip 包。
# 提供免 API Key 的多引擎网络搜索（Baidu / Bing / DuckDuckGo / Sogou）与网页正文提取。
#
# 协议：MCP stdio，逐行 JSON-RPC 交互。
# ==============================================================================
import html
import json
import os
import re
import sys
import urllib.parse
import urllib.request
import ssl

MAX_OUTPUT_CHARS = 30000
DEFAULT_TIMEOUT = 10
USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0"

# 创建忽略 SSL 证书校验的 context，防止手机沙箱内部分系统根证书不全导致请求报错
SSL_CONTEXT = ssl.create_default_context()
SSL_CONTEXT.check_hostname = False
SSL_CONTEXT.verify_mode = ssl.CERT_NONE


def http_get(url, headers=None, timeout=DEFAULT_TIMEOUT):
    """Send an HTTP GET request and return the response text."""
    req_headers = {"User-Agent": USER_AGENT}
    if headers:
        req_headers.update(headers)
    req = urllib.request.Request(url, headers=req_headers)
    with urllib.request.urlopen(req, timeout=timeout, context=SSL_CONTEXT) as resp:
        charset = resp.headers.get_content_charset() or "utf-8"
        content_bytes = resp.read()
        try:
            return content_bytes.decode(charset, errors="replace")
        except Exception:
            return content_bytes.decode("utf-8", errors="replace")


def strip_html_tags(text):
    """Strip HTML tags, decode HTML entities, and normalize whitespace."""
    if not text:
        return ""
    # 移除 script 和 style
    text = re.sub(r"<(script|style|svg|header|footer|nav)[\s\S]*?</\1>", " ", text, flags=re.IGNORECASE)
    # 转换为段落换行
    text = re.sub(r"<(?:p|div|br|li|h[1-6])[\s\S]*?>", "\n", text, flags=re.IGNORECASE)
    # 剥离其余标签
    text = re.sub(r"<[^>]+>", " ", text)
    text = html.unescape(text)
    # 规范化多余空行和空白
    lines = [line.strip() for line in text.splitlines()]
    text = "\n".join(line for line in lines if line)
    return text


# ============================== 搜索引擎实现 ==============================

def search_baidu(query, limit=5):
    """Baidu web search."""
    encoded_query = urllib.parse.quote(query)
    url = f"https://www.baidu.com/s?wd={encoded_query}&rn={max(limit, 10)}"
    html_content = http_get(url, headers={
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
    })
    results = []

    # 提取搜索卡片
    matches = re.findall(
        r'<h3[^>]*class="[^"]*t[^"]*"[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>\s*</h3>[\s\S]*?(?:<div[^>]*class="[^"]*(?:c-abstract|c-span-last|content-right_8Zs40)[^"]*"[^>]*>([\s\S]*?)</div>)?',
        html_content,
        flags=re.IGNORECASE
    )

    for link, title_raw, snippet_raw in matches:
        title = strip_html_tags(title_raw).strip()
        snippet = strip_html_tags(snippet_raw).strip() if snippet_raw else ""
        if title and link:
            results.append({
                "title": title,
                "url": link,
                "snippet": snippet or "No snippet",
            })
        if len(results) >= limit:
            break

    # 若正则未匹配到完整卡片，回退通用 h3 抽取
    if not results:
        h3_matches = re.findall(r'<h3[\s\S]*?<a[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>', html_content, flags=re.IGNORECASE)
        for link, title_raw in h3_matches:
            title = strip_html_tags(title_raw).strip()
            if title and "baidu.com" not in title and link:
                results.append({"title": title, "url": link, "snippet": ""})
            if len(results) >= limit:
                break

    return results


def search_bing(query, limit=5):
    """Bing web search."""
    encoded_query = urllib.parse.quote(query)
    url = f"https://cn.bing.com/search?q={encoded_query}&count={max(limit, 10)}"
    html_content = http_get(url, headers={
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
    })
    results = []

    items = re.findall(
        r'<li[^>]*class="b_algo"[^>]*>[\s\S]*?<h2><a[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a></h2>[\s\S]*?(?:<div[^>]*class="b_caption"[^>]*>[\s\S]*?<p[^>]*>([\s\S]*?)</p>)?',
        html_content,
        flags=re.IGNORECASE
    )

    for link, title_raw, snippet_raw in items:
        title = strip_html_tags(title_raw).strip()
        snippet = strip_html_tags(snippet_raw).strip() if snippet_raw else ""
        if title and link:
            results.append({
                "title": title,
                "url": link,
                "snippet": snippet or "No snippet",
            })
        if len(results) >= limit:
            break

    return results


def search_duckduckgo(query, limit=5):
    """DuckDuckGo HTML search without an API key."""
    encoded_query = urllib.parse.quote(query)
    url = f"https://html.duckduckgo.com/html/?q={encoded_query}"
    html_content = http_get(url)
    results = []

    matches = re.findall(
        r'<a[^>]*class="result__url"[^>]*href="([^"]+)"[^>]*>[\s\S]*?<a[^>]*class="result__snippet"[^>]*>([\s\S]*?)</a>',
        html_content,
        flags=re.IGNORECASE
    )

    for link, snippet_raw in matches:
        actual_url = link
        if "uddg=" in link:
            match_uddg = re.search(r'uddg=([^&]+)', link)
            if match_uddg:
                actual_url = urllib.parse.unquote(match_uddg.group(1))
        snippet = strip_html_tags(snippet_raw).strip()
        results.append({
            "title": snippet[:40] + "..." if len(snippet) > 40 else snippet,
            "url": actual_url,
            "snippet": snippet,
        })
        if len(results) >= limit:
            break

    return results


# ============================== MCP 工具分发 ==============================

def tool_web_search(args):
    """Run a web search and return formatted results."""
    query = (args.get("query") or args.get("q") or "").strip()
    if not query:
        return False, "Missing parameter: query"

    engine = (args.get("engine") or os.environ.get("DEFAULT_SEARCH_ENGINE") or "baidu").lower().strip()
    try:
        limit = int(args.get("limit") or 5)
    except Exception:
        limit = 5
    limit = max(1, min(limit, 15))

    results = []
    error_msg = ""

    # 首选引擎查询
    try:
        if engine == "bing":
            results = search_bing(query, limit)
        elif engine in ("duckduckgo", "ddg"):
            results = search_duckduckgo(query, limit)
        else:
            results = search_baidu(query, limit)
    except Exception as e:
        error_msg = f"{engine} search error: {e}"

    # 若首选引擎失败或无结果，自动回退到 Bing / Baidu 交叉重试
    if not results:
        fallback_engines = ["bing", "baidu", "duckduckgo"]
        for fb in fallback_engines:
            if fb == engine:
                continue
            try:
                if fb == "bing":
                    results = search_bing(query, limit)
                elif fb == "baidu":
                    results = search_baidu(query, limit)
                elif fb == "duckduckgo":
                    results = search_duckduckgo(query, limit)
                if results:
                    break
            except Exception:
                continue

    if not results:
        return False, f"No results found for “{query}”." + (f" ({error_msg})" if error_msg else "")

    output_lines = [f"### Search results (query: {query}, {len(results)} results):\n"]
    for idx, item in enumerate(results, start=1):
        output_lines.append(f"{idx}. **[{item['title']}]({item['url']})**")
        if item.get("snippet"):
            output_lines.append(f"   > {item['snippet']}\n")
        else:
            output_lines.append(f"   Link: {item['url']}\n")

    return True, "\n".join(output_lines)


def tool_fetch_content(args):
    """Fetch a URL and extract the page's main text."""
    url = (args.get("url") or "").strip()
    if not url:
        return False, "Missing parameter: url"
    if not (url.startswith("http://") or url.startswith("https://")):
        url = "https://" + url

    try:
        max_length = int(args.get("max_length") or 6000)
    except Exception:
        max_length = 6000

    try:
        raw_html = http_get(url, timeout=12)
        text = strip_html_tags(raw_html)
        if len(text) > max_length:
            text = text[:max_length] + "\n\n...[content truncated due to length]..."
        return True, f"### Extracted page content ({url}):\n\n{text}"
    except Exception as e:
        return False, f"Failed to fetch page content ({url}): {e}"


TOOLS = {
    "web_search": {
        "description": "Web search tool requiring no API key. Supports multiple engines such as Baidu and Bing and returns titles, snippets, and links",
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "Search keywords or a natural-language question"},
                "engine": {
                    "type": "string",
                    "description": "Search engine (optional: baidu / bing / duckduckgo; default: baidu)",
                    "enum": ["baidu", "bing", "duckduckgo"]
                },
                "limit": {"type": "integer", "description": "Number of results to return (default 5, range 1–15)"},
            },
            "required": ["query"],
        },
        "handler": tool_web_search,
    },
    "fetch_web_content": {
        "description": "Fetch the main content of a webpage and clean it into readable text",
        "inputSchema": {
            "type": "object",
            "properties": {
                "url": {"type": "string", "description": "Target webpage URL (must start with http:// or https://)"},
                "max_length": {"type": "integer", "description": "Maximum number of characters to return (default 6000)"},
            },
            "required": ["url"],
        },
        "handler": tool_fetch_content,
    },
}


# ============================== stdio 主循环 ==============================

def rpc_response(req_id, result):
    return {"jsonrpc": "2.0", "id": req_id, "result": result}


def handle_line(line):
    line = line.strip()
    if not line:
        return None
    try:
        req = json.loads(line)
    except json.JSONDecodeError:
        return None
    method = req.get("method")
    req_id = req.get("id")

    if method == "initialize":
        return rpc_response(req_id, {
            "protocolVersion": "2025-06-18",
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "taixu-websearch-mcp", "version": "1.0.0"},
        })
    if method == "tools/list":
        return rpc_response(req_id, {
            "tools": [
                {
                    "name": name,
                    "description": spec["description"],
                    "inputSchema": spec["inputSchema"],
                }
                for name, spec in TOOLS.items()
            ]
        })
    if method == "tools/call":
        params = req.get("params") or {}
        name = (params.get("name") or "").strip()
        args = params.get("arguments") or {}
        spec = TOOLS.get(name)
        if spec is None:
            return rpc_response(req_id, {
                "content": [{"type": "text", "text": f"Unknown tool: {name}"}],
                "isError": True,
            })
        try:
            ok, text = spec["handler"](args)
        except Exception as e:
            ok, text = False, f"Tool execution error: {e}"
        return rpc_response(req_id, {
            "content": [{"type": "text", "text": text}],
            "isError": not ok,
        })
    return None


def main():
    for line in sys.stdin:
        resp = handle_line(line)
        if resp is not None:
            sys.stdout.write(json.dumps(resp, ensure_ascii=False) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
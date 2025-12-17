package com.check16k.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.time.format.DateTimeFormatter
import java.time.LocalDateTime

object ReportWriter {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun toJson(report: ScanReport, pretty: Boolean = true): String {
        val configured = if (pretty) json else Json
        return configured.encodeToString(report)
    }

    fun writeJson(report: ScanReport, output: Path, pretty: Boolean = true) {
        val content = toJson(report, pretty)
        output.parent?.let { parent ->
            try {
                Files.createDirectories(parent)
            } catch (e: FileAlreadyExistsException) {
                // Directory already exists or parent is a symlink to a directory; ignore.
            }
        }
        Files.writeString(output, content)
    }

    fun toMarkdown(report: ScanReport): String {
        val builder = StringBuilder()
        builder.appendLine("# 16kb-check Report")
        builder.appendLine()
        builder.appendLine("- Artifact: ${report.artifact}")
        report.variant?.let { builder.appendLine("- Variant: $it") }
        builder.appendLine("- Summary: totalSo=${report.summary.totalSo}, fail=${report.summary.fail}, warn=${report.summary.warn}")
        builder.appendLine()
        builder.appendLine("## Failing .so (ELF/ZIP)")
        builder.appendLine()
        builder.appendLine("| Path | ABI | Issues | SHA256 |")
        builder.appendLine("| --- | --- | --- | --- |")
        report.items
            .filter { item -> item.issues.any { it.severity == Severity.FAIL } }
            .forEach { item ->
                val issueText = item.issues
                    .filter { it.severity == Severity.FAIL }
                    .joinToString("<br>") { "${it.type}: ${it.detail}" }
                builder.appendLine("| ${item.path} | ${item.abi ?: "-"} | $issueText | ${item.sha256} |")
            }
        return builder.toString()
    }

    fun writeMarkdown(report: ScanReport, output: Path) {
        val content = toMarkdown(report)
        output.parent?.let { parent ->
            try {
                Files.createDirectories(parent)
            } catch (_: FileAlreadyExistsException) {
                // ignore
            }
        }
        Files.writeString(output, content)
    }

    fun toHtml(report: ScanReport): String {
        val timestamp = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now())
        val failItems = report.items.filter { item -> item.issues.any { it.severity == Severity.FAIL } }
        val warnItems = report.items.filter { item ->
            item.issues.any { it.severity == Severity.WARN } && item.issues.none { it.severity == Severity.FAIL }
        }

        fun issuesCell(item: ScanItem, severity: Severity): String {
            return item.issues
                .filter { it.severity == severity }
                .joinToString("<br/>") { "${escape(it.type.name)}: ${escape(it.detail)}" }
        }

        fun originCell(item: ScanItem): String {
            if (item.origin.isEmpty()) return "<span class=\"text-muted\">unknown</span>"
            return item.origin.joinToString("<br/>") { escape(describeOrigin(it.origin)) }
        }

        return """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="UTF-8"/>
          <title>16kb-check Report - ${report.artifact}</title>
          <style>
            :root {
              --bg: #0a0f1c;
              --panel: #0f172a;
              --panel-2: #111c34;
              --text: #e7edf6;
              --muted: #9ba9c2;
              --fail: #f87171;
              --warn: #fbbf24;
              --ok: #34d399;
              --accent: #38bdf8;
              --border: #1f2a3d;
              --row: #0c1426;
              --shadow: 0 14px 40px rgba(0,0,0,0.35);
            }
            * { box-sizing: border-box; }
            body {
              margin: 0;
              font-family: "Inter", "SF Pro Text", -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
              background:
                radial-gradient(circle at 18% 20%, rgba(56,189,248,0.12), transparent 30%),
                radial-gradient(circle at 82% 12%, rgba(94,234,212,0.10), transparent 28%),
                radial-gradient(circle at 50% 80%, rgba(236,72,153,0.08), transparent 35%),
                var(--bg);
              color: var(--text);
              padding: 24px;
            }
            h1, h2, h3 { margin: 0 0 12px; letter-spacing: 0.3px; }
            .card {
              background: linear-gradient(160deg, rgba(255,255,255,0.04), rgba(255,255,255,0.02));
              border: 1px solid var(--border);
              border-radius: 14px;
              padding: 16px;
              box-shadow: var(--shadow);
              margin-bottom: 18px;
            }
            .summary-grid {
              display: grid;
              grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
              gap: 12px;
            }
            .pill {
              display: inline-flex;
              align-items: center;
              gap: 6px;
              padding: 7px 11px;
              border-radius: 999px;
              font-size: 13px;
              border: 1px solid var(--border);
              background: rgba(255,255,255,0.04);
              line-height: 1.3;
            }
            .pill.fail { color: var(--fail); border-color: rgba(248,113,113,0.5); background: rgba(248,113,113,0.08); }
            .pill.warn { color: var(--warn); border-color: rgba(251,191,36,0.5); background: rgba(251,191,36,0.08); }
            .pill.ok { color: var(--ok); border-color: rgba(52,211,153,0.5); background: rgba(52,211,153,0.08); }
            .pill.muted { color: var(--muted); border-color: rgba(255,255,255,0.1); }
            .table-wrap {
              margin-top: 10px;
              border: 1px solid var(--border);
              border-radius: 12px;
              overflow: hidden;
              background: var(--panel);
              box-shadow: inset 0 1px 0 rgba(255,255,255,0.03);
            }
            table {
              width: 100%;
              border-collapse: collapse;
              font-size: 13px;
            }
            thead {
              background: linear-gradient(90deg, rgba(56,189,248,0.08), rgba(94,234,212,0.04));
            }
            th, td {
              padding: 12px 14px;
              border-bottom: 1px solid var(--border);
              vertical-align: top;
              word-break: break-word;
            }
            th { text-align: left; color: var(--muted); font-weight: 600; letter-spacing: 0.2px; }
            th:not(:last-child), td:not(:last-child) { border-right: 1px solid var(--border); }
            tbody tr:hover { background: rgba(56,189,248,0.05); }
            tbody tr:last-child td { border-bottom: none; }
            code { font-family: "JetBrains Mono", "SFMono-Regular", Consolas, monospace; font-size: 12px; }
            .label { font-weight: 600; color: var(--muted); margin-right: 6px; }
            .issues.fail { color: var(--fail); font-weight: 600; }
            .issues.warn { color: var(--warn); font-weight: 600; }
            .meta { color: var(--muted); font-size: 13px; }
            .section-head {
              display: flex;
              align-items: center;
              justify-content: space-between;
              gap: 8px;
              flex-wrap: wrap;
            }
            .col-path { width: 28%; }
            .col-abi { width: 8%; }
            .col-issues { width: 28%; }
            .col-sha { width: 14%; word-break: break-all; }
            .col-origin { width: 22%; word-break: break-all; }
            .text-muted { color: var(--muted); }
          </style>
        </head>
        <body>
          <div class="card">
            <h1>16kb-check Report</h1>
            <div class="meta">
              <div><span class="label">Artifact:</span><code>${report.artifact}</code></div>
              ${report.variant?.let { "<div><span class=\"label\">Variant:</span><code>$it</code></div>" } ?: ""}
              <div><span class="label">Generated:</span>$timestamp</div>
            </div>
            <div class="summary-grid" style="margin-top:12px;">
              <div class="pill ok">Total: ${report.summary.totalSo}</div>
              <div class="pill fail">Fail: ${report.summary.fail}</div>
              <div class="pill warn">Warn: ${report.summary.warn}</div>
            </div>
          </div>

          <div class="card">
            <div class="section-head">
              <h2>Failing .so</h2>
              <span class="pill fail">Fail: ${failItems.size}</span>
            </div>
            <div class="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th class="col-path">Path</th>
                    <th class="col-abi">ABI</th>
                    <th class="col-issues">Issues</th>
                    <th class="col-sha">SHA256</th>
                    <th class="col-origin">Origin</th>
                  </tr>
                </thead>
                <tbody>
                  ${failItems.joinToString("") { item ->
            """
                  <tr>
                    <td class="col-path"><code>${escape(item.path)}</code></td>
                    <td class="col-abi">${escape(item.abi ?: "-")}</td>
                    <td class="issues fail col-issues">${issuesCell(item, Severity.FAIL)}</td>
                    <td class="col-sha"><code>${escape(item.sha256)}</code></td>
                    <td class="col-origin">${originCell(item)}</td>
                  </tr>
                  """.trimIndent()
        }}
                </tbody>
              </table>
            </div>
          </div>

          <div class="card">
            <div class="section-head">
              <h2>Warnings</h2>
              <span class="pill warn">Warn: ${warnItems.size}</span>
            </div>
            <div class="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th class="col-path">Path</th>
                    <th class="col-abi">ABI</th>
                    <th class="col-issues">Issues</th>
                    <th class="col-sha">SHA256</th>
                    <th class="col-origin">Origin</th>
                  </tr>
                </thead>
                <tbody>
                  ${warnItems.joinToString("") { item ->
            """
                  <tr>
                    <td class="col-path"><code>${escape(item.path)}</code></td>
                    <td class="col-abi">${escape(item.abi ?: "-")}</td>
                    <td class="issues warn col-issues">${issuesCell(item, Severity.WARN)}</td>
                    <td class="col-sha"><code>${escape(item.sha256)}</code></td>
                    <td class="col-origin">${originCell(item)}</td>
                  </tr>
                  """.trimIndent()
        }}
                </tbody>
              </table>
            </div>
          </div>
        </body>
        </html>
        """.trimIndent()
    }

    fun writeHtml(report: ScanReport, output: Path) {
        val content = toHtml(report)
        output.parent?.let { parent ->
            try {
                Files.createDirectories(parent)
            } catch (_: FileAlreadyExistsException) {
                // ignore
            }
        }
        Files.writeString(output, content)
    }

    private fun describeOrigin(origin: Origin): String {
        return when (origin) {
            is Origin.Maven -> "maven:${origin.gav}"
            is Origin.Project -> "project:${origin.path} (${origin.source})"
            is Origin.File -> origin.path
            Origin.Unknown -> "unknown"
        }
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

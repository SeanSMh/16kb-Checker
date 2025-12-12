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
        builder.appendLine("# 16KB Checker Report")
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
                .joinToString("<br/>") { "${it.type}: ${it.detail}" }
        }

        return """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="UTF-8"/>
          <title>16KB Checker Report - ${report.artifact}</title>
          <style>
            :root {
              --bg: #0f172a;
              --panel: #111827;
              --text: #e5e7eb;
              --muted: #9ca3af;
              --fail: #ef4444;
              --warn: #f59e0b;
              --ok: #22c55e;
              --accent: #38bdf8;
              --border: #1f2937;
              --row: #0b1224;
            }
            * { box-sizing: border-box; }
            body {
              margin: 0;
              font-family: "SF Pro Text", "Inter", -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
              background: radial-gradient(circle at 20% 20%, rgba(56,189,248,0.08), transparent 25%),
                          radial-gradient(circle at 80% 0%, rgba(139,92,246,0.08), transparent 18%),
                          var(--bg);
              color: var(--text);
              padding: 24px;
            }
            h1, h2, h3 { margin: 0 0 12px; }
            .card {
              background: linear-gradient(145deg, rgba(255,255,255,0.04), rgba(255,255,255,0.02));
              border: 1px solid var(--border);
              border-radius: 12px;
              padding: 16px;
              box-shadow: 0 10px 30px rgba(0,0,0,0.25);
              margin-bottom: 16px;
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
              padding: 6px 10px;
              border-radius: 999px;
              font-size: 13px;
              border: 1px solid var(--border);
              background: rgba(255,255,255,0.04);
            }
            .pill.fail { color: var(--fail); border-color: rgba(239,68,68,0.5); }
            .pill.warn { color: var(--warn); border-color: rgba(245,158,11,0.5); }
            .pill.ok { color: var(--ok); border-color: rgba(34,197,94,0.5); }
            table {
              width: 100%;
              border-collapse: collapse;
              margin-top: 8px;
              font-size: 13px;
            }
            th, td {
              padding: 10px 12px;
              border-bottom: 1px solid var(--border);
              vertical-align: top;
            }
            th { text-align: left; color: var(--muted); font-weight: 600; letter-spacing: 0.2px; }
            tr:nth-child(even) { background: var(--row); }
            code { font-family: "JetBrains Mono", "SFMono-Regular", Consolas, monospace; font-size: 12px; }
            .label { font-weight: 600; color: var(--muted); margin-right: 6px; }
            .issues.fail { color: var(--fail); }
            .issues.warn { color: var(--warn); }
            .meta { color: var(--muted); font-size: 13px; }
          </style>
        </head>
        <body>
          <div class="card">
            <h1>16KB Checker Report</h1>
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
            <h2>Failing .so</h2>
            <table>
              <thead>
                <tr>
                  <th>Path</th>
                  <th>ABI</th>
                  <th>Issues</th>
                  <th>SHA256</th>
                </tr>
              </thead>
              <tbody>
                ${failItems.joinToString("") { item ->
            """
                <tr>
                  <td><code>${item.path}</code></td>
                  <td>${item.abi ?: "-"}</td>
                  <td class="issues fail">${issuesCell(item, Severity.FAIL)}</td>
                  <td><code>${item.sha256}</code></td>
                </tr>
                """.trimIndent()
        }}
              </tbody>
            </table>
          </div>

          <div class="card">
            <h2>Warnings</h2>
            <table>
              <thead>
                <tr>
                  <th>Path</th>
                  <th>ABI</th>
                  <th>Issues</th>
                  <th>SHA256</th>
                </tr>
              </thead>
              <tbody>
                ${warnItems.joinToString("") { item ->
            """
                <tr>
                  <td><code>${item.path}</code></td>
                  <td>${item.abi ?: "-"}</td>
                  <td class="issues warn">${issuesCell(item, Severity.WARN)}</td>
                  <td><code>${item.sha256}</code></td>
                </tr>
                """.trimIndent()
        }}
              </tbody>
            </table>
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
}

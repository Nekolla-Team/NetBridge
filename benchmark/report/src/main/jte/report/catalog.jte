@import top.tangge233.netbridge.benchmarkreport.index.CatalogData
@param CatalogData data
<!doctype html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>NetBridge Benchmark Reports</title>
    <style>
        :root {
            --bg: #f8fafc;
            --card: #ffffff;
            --text: #0f172a;
            --muted: #64748b;
            --border: #e2e8f0;
            --primary: #0284c7;
            --primary-hover: #0369a1;
        }

        @media (prefers-color-scheme: dark) {
            :root {
                --bg: #0f172a;
                --card: #1e293b;
                --text: #f8fafc;
                --muted: #94a3b8;
                --border: #334155;
                --primary: #38bdf8;
                --primary-hover: #7dd3fc;
            }
        }

        body {
            margin: 0;
            padding: 2rem 1rem;
            background: var(--bg);
            color: var(--text);
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
        }

        .container {
            max-width: 960px;
            margin: 0 auto;
        }

        h1 {
            margin: 0 0 0.5rem 0;
            font-size: 2rem;
        }

        p.sub {
            color: var(--muted);
            margin-bottom: 2rem;
            font-size: 1rem;
        }

        .table-responsive {
            overflow-x: auto;
            background: var(--card);
            border: 1px solid var(--border);
            border-radius: 8px;
        }

        table {
            width: 100%;
            border-collapse: collapse;
            font-size: 0.95rem;
        }

        th {
            background: var(--bg);
            padding: 0.75rem 1rem;
            text-align: left;
            color: var(--muted);
            border-bottom: 2px solid var(--border);
        }

        td {
            padding: 0.75rem 1rem;
            border-bottom: 1px solid var(--border);
        }

        tr:hover td {
            background: var(--bg);
        }

        a {
            color: var(--primary);
            text-decoration: none;
            font-weight: 600;
        }

        a:hover {
            color: var(--primary-hover);
            text-decoration: underline;
        }

        .empty {
            padding: 2rem;
            text-align: center;
            color: var(--muted);
            font-style: italic;
        }
    </style>
</head>
<body>
<div class="container">
    <h1>NetBridge Benchmark Reports</h1>
    <p class="sub">Catalog of recent benchmark task execution reports. Reports represent the most
        recent execution of each task.</p>
    <div class="table-responsive">
        @if(data.getRows().isEmpty())
            <p class="empty">No benchmark reports found yet.</p>
        @else
            <table>
                <thead>
                <tr>
                    <th>Task</th>
                    <th>Suite</th>
                    <th>Last Execution</th>
                    <th>Report</th>
                </tr>
                </thead>
                <tbody>
                @for(var row : data.getRows())
                    <tr>
                        <td><code>${row.getTask()}</code></td>
                        <td>${row.getSuite()}</td>
                        <td>${row.getExecutedAt()}</td>
                        <td><a href="${row.getReportPath()}">View Report &rarr;</a></td>
                    </tr>
                @endfor
                </tbody>
            </table>
        @endif
    </div>
</div>
</body>
</html>

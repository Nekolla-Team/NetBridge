@import top.tangge233.netbridge.benchmarkreport.model.ReportPage

@param ReportPage page
<!doctype html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>${page.getModel().getTitle()} — Benchmark Report</title>
    <style>
        ${page.getCss()}
    </style>
</head>
<body>
<div class="container">
    <header class="report-header">
        <div class="breadcrumb">NetBridge Benchmark Suite</div>
        <h1>${page.getModel().getTitle()}</h1>
        <div class="header-meta">
            <span><strong>Suite:</strong> ${page.getModel().getSuiteTitle()}</span>
            <span><strong>Executed:</strong> ${page.getModel().getExecutedAt()}</span>
            @if(page.getModel().getGit() != null)
                <span><strong>Git:</strong> <code>${page.getModel().getGit()}</code></span>
            @endif
        </div>
    </header>
    @if(!page.getModel().getCards().isEmpty())
        <div class="card-grid">
            @for(var card : page.getModel().getCards())
                <div class="metric-card">
                    <div class="metric-card-label">${card.getLabel()}</div>
                    <div class="metric-card-value">${card.getValue()}</div>
                    @if(card.getSub() != null)
                        <div class="metric-card-sub">${card.getSub()}</div>
                    @endif
                </div>
            @endfor
        </div>
    @endif
    @if(!page.getModel().getScope().isEmpty())
        <section class="report-section">
            <h2>Execution Scope</h2>
            <div class="scope-grid">
                @for(var item : page.getModel().getScope())
                    <div class="scope-item">
                        <span class="scope-key">${item.getKey()}</span>
                        <span class="scope-val">${item.getValue()}</span>
                    </div>
                @endfor
            </div>
        </section>
    @endif
    @for(var section : page.getModel().getSections())
        @if(section.getCollapsible())
            <section class="report-section">
                <details class="collapsible-details">
                    <summary><h2>${section.getTitle()}</h2></summary>
                    @if(section.getNote() != null && section.getRows().isEmpty())
                        <p class="empty-msg">${section.getNote()}</p>
                    @else
                        <div class="table-responsive">
                            <table class="data-table keyval-table">
                                <thead>
                                <tr>
                                    @for(var header : section.getHeaders())
                                        <th>${header}</th>
                                    @endfor
                                </tr>
                                </thead>
                                <tbody>
                                @for(var row : section.getRows())
                                    <tr>
                                        @for(var cell : row)
                                            @if(cell.getValue().getNumeric())
                                                @if(cell.title() != null)
                                                    <td class="num"
                                                        title="${cell.title()}">${cell.text()}</td>
                                                @else
                                                    <td class="num">${cell.text()}</td>
                                                @endif
                                            @elseif(cell.getValue().getCode())
                                                <td><code>${cell.text()}</code></td>
                                            @else
                                                <td>${cell.text()}</td>
                                            @endif
                                        @endfor
                                    </tr>
                                @endfor
                                </tbody>
                            </table>
                        </div>
                    @endif
                </details>
            </section>
        @else
            <section class="report-section">
                <h2>${section.getTitle()}</h2>
                @if(section.getDescription() != null)
                    <p class="section-desc">${section.getDescription()}</p>
                @endif
                @if(section.getNote() != null && section.getRows().isEmpty())
                    <p class="empty-msg">${section.getNote()}</p>
                @else
                    <div class="table-responsive">
                        <table class="data-table">
                            <thead>
                            <tr>
                                @for(var header : section.getHeaders())
                                    <th>${header}</th>
                                @endfor
                            </tr>
                            </thead>
                            <tbody>
                            @for(var row : section.getRows())
                                <tr>
                                    @for(var cell : row)
                                        @if(cell.getValue().getNumeric())
                                            @if(cell.title() != null)
                                                <td class="num"
                                                    title="${cell.title()}">${cell.text()}</td>
                                            @else
                                                <td class="num">${cell.text()}</td>
                                            @endif
                                        @elseif(cell.getValue().getCode())
                                            <td><code>${cell.text()}</code></td>
                                        @else
                                            <td>${cell.text()}</td>
                                        @endif
                                    @endfor
                                </tr>
                            @else
                                <tr>
                                    <td colspan="${section.getHeaders().size()}">No rows recorded.
                                    </td>
                                </tr>
                            @endfor
                            </tbody>
                        </table>
                    </div>
                @endif
            </section>
        @endif
    @endfor
    @if(!page.getModel().getArtifacts().isEmpty())
        <section class="report-section">
            <h2>Raw Artifacts</h2>
            <ul class="artifact-list">
                @for(var artifact : page.getModel().getArtifacts())
                    <li><a href="${artifact.getKey()}">${artifact.getKey()}</a>
                        &mdash; ${artifact.getValue()}</li>
                @endfor
            </ul>
        </section>
    @endif
    <footer class="report-footer">
        <p>NetBridge On-Demand Benchmark Suite &bull;
            Generated ${page.getModel().getGeneratedAt()}</p>
    </footer>
</div>
</body>
</html>

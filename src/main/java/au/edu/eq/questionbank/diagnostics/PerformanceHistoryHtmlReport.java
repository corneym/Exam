package au.edu.eq.questionbank.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Generates a self-contained offline HTML report from permanent historical
 * checkpoint summaries.
 */
public final class PerformanceHistoryHtmlReport {

	private static final Pattern SUMMARY_NAME = Pattern.compile("checkpoint-[0-9]{6}\\.csv");
	private static final String EXPECTED_HEADER = "checkpoint_at,machine_id,session_id,checkpoint_index,"
			+ "application_version,runtime_kind,schema_version," + "question_count,database_bytes,operation,samples,"
			+ "failures,median_ms,p90_ms,max_ms";
	private static final String HTML = """
			<!doctype html>
			<html lang="en">
			<head>
			  <meta charset="UTF-8">
			  <meta name="viewport" content="width=device-width,initial-scale=1">
			  <title>Exam Question Bank — Performance History</title>
			  <style>
			    body {
			      font-family: system-ui, sans-serif;
			      margin: 0 auto;
			      padding: 28px;
			      max-width: 1250px;
			      background: #f8fafc;
			      color: #172033;
			    }
			    h1 { margin-bottom: 6px; }
			    .subtitle { color: #64748b; margin-top: 0; }
			    .panel {
			      background: white;
			      border: 1px solid #dce3ec;
			      border-radius: 10px;
			      padding: 20px;
			      margin-top: 20px;
			    }
			    .filters {
			      display: flex;
			      flex-wrap: wrap;
			      gap: 18px;
			      align-items: end;
			    }
			    label {
			      display: grid;
			      gap: 6px;
			      font-size: 13px;
			      font-weight: 600;
			    }
			    select {
			      font: inherit;
			      padding: 8px;
			      min-width: 150px;
			      border: 1px solid #cbd5e1;
			      border-radius: 5px;
			      background: white;
			    }
			    svg {
			      display: block;
			      width: 100%;
			      height: auto;
			    }
			    .legend { display: flex; flex-wrap: wrap; gap: 14px; }
			    .legend span { font-size: 13px; }
			    .swatch {
			      display: inline-block;
			      width: 12px;
			      height: 12px;
			      margin-right: 5px;
			    }
			    .scroll { overflow-x: auto; }
			    table {
			      width: 100%;
			      border-collapse: collapse;
			      font-size: 13px;
			    }
			    th, td {
			      padding: 9px;
			      border-bottom: 1px solid #e2e8f0;
			      text-align: left;
			      white-space: nowrap;
			    }
			    th { background: #f1f5f9; }
			    .note { color: #64748b; font-size: 13px; }
			  </style>
			</head>
			<body>
			  <h1>Exam Question Bank — Performance History</h1>
			  <p class="subtitle">
			    Offline observations grouped by machine, application version
			    and Question corpus size.
			  </p>

			  <section class="panel filters">
			    <label>Operation
			      <select id="operation"></select>
			    </label>
			    <label>Machine
			      <select id="machine"></select>
			    </label>
			    <label>Application version
			      <select id="version"></select>
			    </label>
			    <label>Timing statistic
			      <select id="metric">
			        <option value="median">Median (ms)</option>
			        <option value="p90">P90 (ms)</option>
			        <option value="maximum">Maximum (ms)</option>
			      </select>
			    </label>
			  </section>

			  <section class="panel">
			    <h2>Performance versus Question count</h2>
			    <svg id="chart" viewBox="0 0 940 390"
			         role="img"
			         aria-label="Operation timing versus Question count"></svg>
			    <div id="legend" class="legend"></div>
			    <p class="note">
			      Each series belongs to one machine and application version.
			      Points are checkpoint observations, not synthetic benchmarks.
			      A trend is correlation, not proof of its cause.
			    </p>
			  </section>

			  <section class="panel">
			    <h2>Underlying observations</h2>
			    <p id="count" class="note"></p>
			    <div class="scroll">
			      <table>
			        <thead>
			          <tr>
			            <th>Machine</th><th>Version</th>
			            <th>Environment</th><th>Questions</th>
			            <th>Operation</th><th>Samples</th>
			            <th>Failures</th><th>Median ms</th>
			            <th>P90 ms</th><th>Max ms</th>
			            <th>Checkpoint UTC</th>
			          </tr>
			        </thead>
			        <tbody id="observations"></tbody>
			      </table>
			    </div>
			  </section>

			  <script>
			    const observations = __OBSERVATIONS__;

			    const operation = document.getElementById("operation");
			    const machine = document.getElementById("machine");
			    const version = document.getElementById("version");
			    const metric = document.getElementById("metric");

			    function populate(select, values, includeAll) {
			      if (includeAll) {
			        select.add(new Option("All", "*"));
			      }
			      for (const value of [...new Set(values)].sort()) {
			        select.add(new Option(value, value));
			      }
			    }

			    populate(operation, observations.map(o => o.operation), false);
			    populate(machine, observations.map(o => o.machine), true);
			    populate(version, observations.map(o => o.version), true);

			    const svgNS = "http://www.w3.org/2000/svg";

			    function element(name, attributes, text) {
			      const node = document.createElementNS(svgNS, name);
			      for (const [key, value] of Object.entries(attributes)) {
			        node.setAttribute(key, String(value));
			      }
			      if (text !== undefined) node.textContent = text;
			      return node;
			    }

			    function render() {
			      const filtered = observations.filter(o =>
			        o.operation === operation.value &&
			        (machine.value === "*" || o.machine === machine.value) &&
			        (version.value === "*" || o.version === version.value)
			      );

			      const body = document.getElementById("observations");
			      body.replaceChildren();

			      for (const o of filtered) {
			        const row = document.createElement("tr");
			        for (const value of [
			          o.machine, o.version, o.runtime, o.questions,
			          o.operation, o.samples, o.failures,
			          o.median.toFixed(2), o.p90.toFixed(2),
			          o.maximum.toFixed(2), o.captured
			        ]) {
			          const cell = document.createElement("td");
			          cell.textContent = String(value);
			          row.appendChild(cell);
			        }
			        body.appendChild(row);
			      }

			      document.getElementById("count").textContent =
			        filtered.length + " checkpoint observation(s)";

			      const chart = document.getElementById("chart");
			      const legend = document.getElementById("legend");
			      chart.replaceChildren();
			      legend.replaceChildren();

			      const points = filtered.filter(o =>
			        o.questions >= 0 && Number.isFinite(o[metric.value])
			      );

			      if (!points.length) {
			        chart.appendChild(element(
			          "text", {x: 70, y: 180, fill: "#64748b"},
			          "No observations for these filters"));
			        return;
			      }

			      const minX = Math.min(...points.map(o => o.questions));
			      const maxX = Math.max(...points.map(o => o.questions));
			      const maxY = Math.max(
			        1, ...points.map(o => o[metric.value]));

			      const x = value =>
			        75 + 820 * (value - minX) / Math.max(1, maxX - minX);

			      const y = value =>
			        325 - 270 * value / maxY;

			      chart.appendChild(element(
			        "line", {x1:75,y1:325,x2:895,y2:325,
			                 stroke:"#64748b"}));

			      chart.appendChild(element(
			        "line", {x1:75,y1:45,x2:75,y2:325,
			                 stroke:"#64748b"}));

			      chart.appendChild(element(
			        "text", {x:430,y:372,fill:"#334155"},
			        "Questions in corpus"));

			      chart.appendChild(element(
			        "text", {x:80,y:30,fill:"#334155"},
			        metric.value + " elapsed time (ms)"));

			      chart.appendChild(element(
			        "text", {x:75,y:347,fill:"#64748b"},
			        String(minX)));

			      chart.appendChild(element(
			        "text", {x:852,y:347,fill:"#64748b"},
			        String(maxX)));

			      chart.appendChild(element(
			        "text", {x:10,y:58,fill:"#64748b"},
			        maxY.toFixed(1)));

			      chart.appendChild(element(
			        "text", {x:42,y:325,fill:"#64748b"},
			        "0"));

			      const series = new Map();

			      for (const point of points) {
			        const key = point.machine + " / " + point.version;
			        if (!series.has(key)) series.set(key, []);
			        series.get(key).push(point);
			      }

			      const colours = [
			        "#2563eb", "#c2410c", "#047857",
			        "#9333ea", "#b45309", "#0891b2"
			      ];

			      let seriesIndex = 0;

			      for (const [name, values] of series) {
			        values.sort((a,b) =>
			          a.questions - b.questions ||
			          a.captured.localeCompare(b.captured));

			        const colour = colours[seriesIndex++ % colours.length];
			        const coords = values.map(o =>
			          x(o.questions) + "," + y(o[metric.value])).join(" ");

			        chart.appendChild(element(
			          "polyline",
			          {points:coords,fill:"none",stroke:colour,
			           "stroke-width":2.5}));

			        for (const point of values) {
			          const dot = element(
			            "circle",
			            {cx:x(point.questions),cy:y(point[metric.value]),
			             r:4,fill:colour});

			          dot.appendChild(element(
			            "title", {},
			            name + ": " + point.questions + " Questions, "
			            + point[metric.value].toFixed(2) + " ms"));

			          chart.appendChild(dot);
			        }

			        const item = document.createElement("span");
			        const swatch = document.createElement("span");
			        swatch.className = "swatch";
			        swatch.style.background = colour;
			        item.appendChild(swatch);
			        item.appendChild(document.createTextNode(name));
			        legend.appendChild(item);
			      }
			    }

			    for (const select of [operation,machine,version,metric]) {
			      select.addEventListener("change", render);
			    }
			    render();
			  </script>
			</body>
			</html>
			""";

	/**
	 * Creates an offline performance-history report generator.
	 */
	public PerformanceHistoryHtmlReport() {
	}

	private static List<String> csvCells(String row) throws IOException {
		List<String> cells = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < row.length(); i++) {
			char character = row.charAt(i);
			if (character == '"') {
				if (quoted && i + 1 < row.length() && row.charAt(i + 1) == '"') {
					current.append('"');
					i++;
				} else {
					quoted = !quoted;
				}
			} else if (character == ',' && !quoted) {
				cells.add(current.toString());
				current.setLength(0);
			} else {
				current.append(character);
			}
		}
		if (quoted) {
			throw new IOException("Unterminated quoted historical CSV field");
		}
		cells.add(current.toString());
		return cells;
	}

	private static String jsonString(String value) {
		StringBuilder escaped = new StringBuilder("\"");
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
			case '"' -> escaped.append("\\\"");
			case '\\' -> escaped.append("\\\\");
			case '\n' -> escaped.append("\\n");
			case '\r' -> escaped.append("\\r");
			case '\t' -> escaped.append("\\t");
			case '<' -> escaped.append("\\u003c");
			case '>' -> escaped.append("\\u003e");
			case '&' -> escaped.append("\\u0026");
			default -> {
				if (c < 32 || c == '\u2028' || c == '\u2029') {
					escaped.append("\\u%04x".formatted((int) c));
				} else {
					escaped.append(c);
				}
			}
			}
		}
		return escaped.append('"').toString();
	}

	private static List<Observation> read(Path historyDirectory) throws IOException {
		if (!Files.isDirectory(historyDirectory)) {
			return new ArrayList<>();
		}
		List<Observation> result = new ArrayList<>();
		try (Stream<Path> candidates = Files.walk(historyDirectory, 3)) {
			for (Path file : candidates.filter(Files::isRegularFile).filter(path -> !Files.isSymbolicLink(path))
					.filter(path -> SUMMARY_NAME.matcher(path.getFileName().toString()).matches()).toList()) {
				Path relative = historyDirectory.relativize(file);
				if (relative.getNameCount() != 3) {
					continue;
				}
				UUID directoryMachine;
				UUID directorySession;
				try {
					directoryMachine = UUID.fromString(relative.getName(0).toString());
					directorySession = UUID.fromString(relative.getName(1).toString());
				} catch (IllegalArgumentException invalid) {
					throw new IOException("Invalid history directory identity", invalid);
				}
				List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
				if (lines.isEmpty() || !EXPECTED_HEADER.equals(lines.getFirst())) {
					throw new IOException("Unsupported historical summary format");
				}
				for (int line = 1; line < lines.size(); line++) {
					if (lines.get(line).isBlank()) {
						continue;
					}
					try {
						List<String> values = csvCells(lines.get(line));
						if (values.size() != 15) {
							throw new IllegalArgumentException("Incorrect summary column count");
						}
						Observation observation = new Observation(Instant.parse(values.get(0)),
								UUID.fromString(values.get(1)), UUID.fromString(values.get(2)), values.get(4),
								values.get(5), Long.parseLong(values.get(7)), values.get(9),
								Long.parseLong(values.get(10)), Long.parseLong(values.get(11)),
								Double.parseDouble(values.get(12)), Double.parseDouble(values.get(13)),
								Double.parseDouble(values.get(14)));
						if (!observation.machineId().equals(directoryMachine)
								|| !observation.sessionId().equals(directorySession) || observation.samples() < 1
								|| observation.failures() < 0 || observation.failures() > observation.samples()
								|| !Double.isFinite(observation.median()) || !Double.isFinite(observation.p90())
								|| !Double.isFinite(observation.maximum()) || observation.median() < 0
								|| observation.p90() < 0 || observation.maximum() < 0) {
							throw new IllegalArgumentException("Invalid historical observation");
						}
						result.add(observation);
					} catch (RuntimeException invalid) {
						throw new IOException("Invalid summary row in " + file.getFileName() + " at line " + (line + 1),
								invalid);
					}
				}
			}
		}
		return result;
	}

	/**
	 * Generates a new offline report without modifying historical records.
	 *
	 * @param historyDirectory permanent historical-summary directory
	 * @param destination      new HTML output file
	 * @return generated report path
	 * @throws IOException if the history cannot be read or output written
	 */
	public Path generate(Path historyDirectory, Path destination) throws IOException {
		List<Observation> observations = read(historyDirectory);
		if (observations.isEmpty()) {
			throw new IOException("No historical performance observations available");
		}
		observations.sort(Comparator.comparing(Observation::operation).thenComparing(o -> o.machineId().toString())
				.thenComparing(Observation::questionCount).thenComparing(Observation::capturedAt));
		StringBuilder json = new StringBuilder("[");
		for (Observation observation : observations) {
			if (json.length() > 1) {
				json.append(',');
			}
			json.append('{').append("\"machine\":").append(jsonString(observation.machineId().toString()))
					.append(",\"session\":").append(jsonString(observation.sessionId().toString()))
					.append(",\"version\":").append(jsonString(observation.version())).append(",\"runtime\":")
					.append(jsonString(observation.runtime())).append(",\"operation\":")
					.append(jsonString(observation.operation())).append(",\"captured\":")
					.append(jsonString(observation.capturedAt().toString())).append(",\"questions\":")
					.append(observation.questionCount()).append(",\"samples\":").append(observation.samples())
					.append(",\"failures\":").append(observation.failures()).append(",\"median\":")
					.append(observation.median()).append(",\"p90\":").append(observation.p90()).append(",\"maximum\":")
					.append(observation.maximum()).append('}');
		}
		json.append(']');
		String html = HTML.replace("__OBSERVATIONS__", json.toString());
		Path output = destination.toAbsolutePath().normalize();
		Path parent = output.getParent();
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, ".performance-report-", ".tmp");
		try {
			Files.writeString(temporary, html, StandardCharsets.UTF_8);

			// Never overwrite a previous report silently.
			Files.move(temporary, output);
		} finally {
			Files.deleteIfExists(temporary);
		}
		return output;
	}

	private record Observation(Instant capturedAt, UUID machineId, UUID sessionId, String version, String runtime,
			long questionCount, String operation, long samples, long failures, double median, double p90,
			double maximum) {
	}
}

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { execFile } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

// This file lives at .claude/mcp-servers/devassist-tools/server.mjs, three
// levels below the project root.
const PROJECT_ROOT = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../../.."
);

function runMaven(args) {
  return new Promise((resolve) => {
    execFile(
      "./mvnw",
      args,
      { cwd: PROJECT_ROOT, maxBuffer: 20 * 1024 * 1024 },
      (error, stdout, stderr) => {
        resolve({ error, stdout, stderr });
      }
    );
  });
}

function summarize(stdout) {
  const lines = stdout.split("\n");
  const summaryLine = lines.find((l) => l.includes("Tests run:"));
  const success = /BUILD SUCCESS/.test(stdout);
  const failedTests = lines
    .filter((l) => /^\[ERROR\].*Test.*(FAILED|Error)/i.test(l) || /^\[ERROR\]   /.test(l))
    .slice(0, 20);
  return { success, summaryLine: summaryLine ?? null, failedTests };
}

const server = new McpServer({ name: "devassist-tools", version: "1.0.0" });

server.registerTool(
  "run_maven_tests",
  {
    title: "Run Maven tests",
    description:
      "Run the DevAssist JUnit test suite via the Maven wrapper (./mvnw test). " +
      "Optionally scope to a single test class. Returns build status, the " +
      "'Tests run' summary line, and any failing test names.",
    inputSchema: {
      testClass: z
        .string()
        .optional()
        .describe(
          "Fully qualified or simple test class name to run in isolation, e.g. 'ProjectServiceTest'. Omit to run the full suite."
        ),
    },
  },
  async ({ testClass }) => {
    const args = ["test"];
    if (testClass) {
      args.push(`-Dtest=${testClass}`);
    }

    const { error, stdout, stderr } = await runMaven(args);
    const { success, summaryLine, failedTests } = summarize(stdout);

    const report = {
      success,
      exitError: error ? error.message : null,
      summaryLine,
      failedTests,
    };

    return {
      content: [
        {
          type: "text",
          text: JSON.stringify(report, null, 2),
        },
      ],
      isError: !success,
    };
  }
);

server.registerTool(
  "check_coverage",
  {
    title: "Check test coverage (stub)",
    description:
      "Return code coverage metrics for the DevAssist project. STUB: no coverage " +
      "tool (e.g. JaCoCo) is configured in pom.xml yet, so this returns mock " +
      "placeholder data, not a real measurement. Use it to validate the tool " +
      "wiring; do not treat the numbers as real until JaCoCo is added.",
    inputSchema: {
      packageName: z
        .string()
        .optional()
        .describe(
          "Java package to scope the (mock) report to, e.g. 'com.devassist.project'. Omit for the whole project."
        ),
    },
  },
  async ({ packageName }) => {
    const report = {
      mocked: true,
      note: "No coverage tool is configured in pom.xml. Add the jacoco-maven-plugin and re-implement this handler to parse target/site/jacoco/jacoco.xml for real numbers.",
      scope: packageName ?? "com.devassist (whole project)",
      lineCoveragePercent: 0,
      branchCoveragePercent: 0,
    };

    return {
      content: [
        {
          type: "text",
          text: JSON.stringify(report, null, 2),
        },
      ],
    };
  }
);

const transport = new StdioServerTransport();
await server.connect(transport);

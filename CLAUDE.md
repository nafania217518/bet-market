# CLAUDE.md

## Rules

1. Write replies and documentation "80% of the way to ASD-STE100" (Simplified Technical English): short sentences (about 20 words max), one idea per sentence, active voice, present tense, one approved meaning per word, no idioms, no filler. Do not go full STE: keep natural phrasing and domain terms where strict STE would read awkwardly.
2. KISS: keep it simple.
3. TDD approach for coding: Requirement -> Test -> Code. The requirement is the test name. New requirement = new test. The task is done when all requirements have tests and all tests pass.
4. Use low-effort sub-agents to write code. Instruct them to follow the TDD approach and give them the necessary set of requirements and instructions.
5. Tests must pass. Coverage must be no less than 90% per core functionality module.
6. Tests for every feature, no exceptions. Every feature ships with its test(s) unless the user explicitly states otherwise for that feature.
7. TDD on bug reports. When the user reports a bug, first add test(s). Only after those tests reproduce the bug, fix it.
8. TDD: tests are the only proof code works. All code must have test coverage via unit tests and/or functional, integration, browser, or end-to-end tests. Frontend: unit and/or browser tests. Backend: unit and/or integration tests. Both are acceptable per surface.
9. NEVER use `nohup` or `command &` to start a background task. Such tasks cannot be tracked and cause unexpected errors. ALWAYS start background tasks through the tools (`run_in_background=true`) so the task stays tracked and can be stopped with the corresponding stop tools.
10. Do not use the built-in web search. It is broken. Use the `mcp-browser` MCP tools to search the web and read sites.
11. You may have outdated information. Do research with `mcp-browser` and check at least 4 sources for each claim you make. Put anything worth noticing under the Caveats section below in the format `SHORT_DESCRIPTION : file_reference.md`.

## Caveats
- Spring Boot 3.x is end-of-life; the project uses Spring Boot 4.1 (Jackson 3, modular starters, Testcontainers 2) with Spring Cloud 2025.1.3 : docs/caveats/spring-boot-4.md

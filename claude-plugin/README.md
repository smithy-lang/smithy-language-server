# Claude Code plugin for smithy-language-server

A [Claude Code](https://docs.claude.com/en/docs/agents-and-tools/claude-code) plugin that wires `smithy-language-server` into Claude Code's `LSP` tool. The plugin is co-located with the language server so the two ship and version together.

## What you get

- Real-time diagnostics on `.smithy` files surfaced to the agent after every edit
- `documentSymbol`, `hover`, `goToDefinition`, `findReferences` over Claude Code's `LSP` tool
- AWS trait resolution (`aws.protocols#restJson1`, `aws.api#service`, ...) once external dependencies have been materialized for the project (e.g. by running `smithy build` so the language server can resolve them through `smithy-build.json`)

## Install

The plugin is distributed through plugin marketplaces. Once a marketplace is configured:

```
claude plugin install smithy-lsp@<marketplace>
```

On the first session after install, the `SessionStart` hook downloads the matching `smithy-language-server` release into the plugin's data directory and symlinks the launcher into `~/.local/bin` (override with `SMITHY_LSP_BIN_DIR=...`).

The plugin pins to a specific language-server version (see `.claude-plugin/plugin.json`); override at runtime with `SMITHY_LSP_VERSION=0.8.0` in the environment.

## Layout

```
claude-plugin/
├── .claude-plugin/plugin.json    # Manifest declaring lspServers.smithy
├── hooks/hooks.json              # SessionStart hook entry
├── scripts/install-binary.sh     # Downloads + symlinks the binary
└── README.md                     # This file
```

## How it works

Claude Code reads `.claude-plugin/plugin.json` and registers the LSP for `.smithy` files. When the agent or editor opens a `.smithy` file, Claude Code spawns `smithy-language-server` over stdio and routes LSP requests to it. Diagnostics arrive back as `<new-diagnostics>` system reminders in the conversation.

The `SessionStart` hook ensures the binary exists and is reachable before any LSP traffic is routed.

## Limitations

- Claude Code 2.1.x initializes the LSP with `workspaceFolders = [process.cwd()]` and does not call `workspace/didChangeWorkspaceFolders`. To get cross-file `goToDefinition` and `workspaceSymbol` on a multi-file model, launch `claude` from the package directory containing `smithy-build.json`.
- `lspServers.command` is resolved via `$PATH` only; `${CLAUDE_PLUGIN_DATA}` substitution is not honored for LSP commands (only for MCP commands). The install script therefore symlinks the launcher into `~/.local/bin`.
- The plugin manifest schema accepts `restartOnCrash` and `shutdownTimeout`, but Claude Code 2.1.x rejects them at runtime with a swallowed error. They are intentionally absent from the manifest here.

## Vending

This plugin lives in the `smithy-language-server` repository so it ships with the language server itself. To distribute through a marketplace, reference this plugin's directory from a marketplace `marketplace.json` entry, e.g.:

```json
{
  "name": "smithy-lsp",
  "source": {
    "source": "git-subdir",
    "url": "https://github.com/smithy-lang/smithy-language-server.git",
    "path": "claude-plugin",
    "ref": "main"
  }
}
```

Submission to Anthropic's [official marketplace](https://github.com/anthropics/claude-code) is the path to "verified vendor" listing alongside `jdtls-lsp`, `gopls-lsp`, and the other first-party LSP plugins.

## License

Apache-2.0, same as the parent project.

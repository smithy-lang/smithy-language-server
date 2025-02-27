## Smithy Language Server
[![Build Status](https://github.com/awslabs/smithy-language-server/workflows/ci/badge.svg)](https://github.com/awslabs/smithy-language-server/actions/workflows/ci.yml)

A [Language Server Protocol](https://microsoft.github.io/language-server-protocol/)
implementation for the [Smithy IDL](https://smithy.io).

Smithy is a protocol-agnostic interface definition language and set of tools for generating clients, servers, and documentation for any programming language.

## Features
The Smithy Language Server provides the following features:
* Code Completion
* Code Folding
* Code Hover Information
* Code Inlay Hints
* Code Navigation (Definition / Declaration)
* Document Symbol Support
* Document Formatting

## Running the Language Server

### Visual Studio Code Extension
For Visual Studio Code users, please install our official Visual Studio Code extension from the marketplace: 

[Smithy Extension for Visual Studio Code](https://marketplace.visualstudio.com/items?itemName=smithy.smithy-vscode-extension)

### Neovim
For users who prefer Neovim,  [Neovim lspconfig](https://github.com/neovim/nvim-lspconfig/tree/master) can be used to configure the Smithy Language Server. For detailed instruction, please check 
the [Manual Setup (Neovim)](#neovim-jdk-21-is-required) section below.

### Manual Setup
You can also run the language server manually.

#### JAR Release (JDK 21 is required)
1. Download the latest release JAR from [release page](https://github.com/smithy-lang/smithy-language-server/releases).
2. Unzip the downloaded zip file.
3. Run the language server by executing the following command:
      ```bash
      java -jar /path/to/unziped/folder/smithy-language-server-0.x.x.jar [port-number]
      // Usually the default port-number is set to 0
      ```

#### Neovim (JDK 21 is required)
1. Download the latest release JAR from [release page](https://github.com/smithy-lang/smithy-language-server/releases).
2. Unzip the downloaded zip file.
3. Check out the [Neovim lspconfig Guide](https://github.com/neovim/nvim-lspconfig/blob/master/doc/configs.md#smithy_ls) and configure the `init.lua`
   to setup the Smithy Language Server. A sample configuration would look like this:

      ```lua
      -- init.lua
      ....
      
      local lspconfig = require('lspconfig')
      local smithy_jar_path = "/path/to/lsp/lib/smithy-language-server-0.x.x.jar"  -- Adjust the path
      
      -- Configure the Smithy Language Server
      lspconfig.smithy_ls.setup{
        -- Specify the command to start the Smithy Language Server
        cmd = {"java", "-jar", smithy_jar_path, "0" }
      }
      
      ...
      ```

## Configuring the Language Server

### Client-Side Configuration

Configurations supported from client:

| Field                       | Description                                                       |  Type   |     Default     |               Options                |
|-----------------------------|-------------------------------------------------------------------|:-------:|:---------------:|:------------------------------------:|
| Diagnostics:MinimumSeverity | Sets the minimum severity level for diagnostics.                  | string  |    "WARNING"    | "WARNING", "NOTE", "DANGER", "ERROR" |
| Only Read On Save           | When enabled, only reloads the model on file save.                | boolean |      false      |             false, true              |
| 
Configurations supported from Visual Studio Code:

| Field                  | Description                                                       |  Type   |     Default     |               Options                |
|------------------------|-------------------------------------------------------------------|:-------:|:---------------:|:------------------------------------:|
| Max Number Of Problems | Controls the maximum number of problems produced by the server.   | integer |       100       |                 N/A                  |
| Root Path              | The root path of the Smithy project                               | string  |      null       |         Valid directory path         |
| Trace:Server           | Traces the communication between VS Code and the language server. | string  |    "verbose"    |     "verbose", "messages", "off"     |
| Version                | Version of the Smithy Language Server                             | string  | current version | Valid Smithy Language Server version |
### Build File Configuration

The Smithy Language Server can recognize two types of : `smithy-build.json` or `.smithy-project.json`. The build files help the server to locate the root path of 
the Smithy project.

Table of fields used by the Smithy Language Server from build files:

| Field           | Description                                                                                                         |  Type  |
|-----------------|---------------------------------------------------------------------------------------------------------------------|:------:|
| sources         | A list of relative files or directories that contain the models that are considered the source models of the build. |  list  |
| imports         | A list of model files and directories to load when validating and building the model.                               |  list  |
| dependencies    | A list of dependencies used to bring in model imports, build plugins, validators, transforms, and other extensions. |  list  |       

#### smithy-build.json
The Smithy Language Server fully supports `smithy-build.json` and resolve any `sources`, `imports`, and `dependencies` you specify in the config file.
Detailed documentation of `smithy-build.json` can be found from [Smithy:Using smithy-build.json](https://smithy.io/2.0/guides/smithy-build-json.html#using-smithy-build-json)


`smithy-build.json` config file example:

```json
{
      "version": "1.0",
      "sources": ["model"],
      "imports": ["foo.json", "baz.json"],
      "outputDirectory": "build/output",
      "maven": {
        "dependencies": ["software.amazon.smithy:smithy-aws-traits:1.26.1"],
        "repositories": [
          {
            "url": "https://example.com/maven",
            "httpCredentials": "${MAVEN_USER}:${MAVEN_PASSWORD}"
          }
        ]
        },
      "projections": {
        "projection-name": {
          "transforms": [
            {
              "name": "transform-name",
              "args": [
                "argument1",
                "argument2",
                "..."
              ]
            },
            {
              "name": "other-transform"
            }
          ],
          "plugins": {
            "plugin-name": {
              "plugin-config": "value"
            },
            "...": {}
          }
        }
      },
      "plugins": {
        "plugin-name": {
          "plugin-config": "value"
        },
        "...": {}
      }
}
```

#### .smithy-project.json

`.smithy-project.json` is an alternative way to configure your Smithy project when the config is not specified in your `smithy-build.json`. 
For projects using specific build tools(e.g., `smithy-gradle-plugin` ), you can configure `.smithy-project.json` to specify dependencies and other project settings.

`.smithy-project.json` config file example:

```json
{
    "sources": [
      "model",
      "src/main/smithy"
    ],
    "imports": [
      "vendor/smithy-models"
    ],
    "dependencies": [
      {
        "name": "smithy-test-traits",
        "path": "./././/smithy-test-traits.jar"
      }
    ],
    "outputDirectory": "/foo/bar"
}
```

## Security

See [CONTRIBUTING](CONTRIBUTING.md#security-issue-notifications) for more information.

## License

This project is licensed under the Apache-2.0 License.
# Smithy Language Server

[![Build Status](https://github.com/smithy-lang/smithy-language-server/workflows/ci/badge.svg)](https://github.com/smithy-lang/smithy-language-server/actions/workflows/ci.yml)

A [Language Server Protocol][lsp] implementation for the [Smithy IDL][smithy].

## Installation

If you're using VSCode, the [Smithy extension][smithy-vscode] manages the smithy-language-server
installation itself, so you don't need to download it. For other editors, follow
these steps to install and run smithy-language-server.

### Pre-built Installation

Zips of self-contained smithy-language-server installations for various platforms
can be downloaded from the [Releases][releases] page. Each zip contains a launch script,
`bin/smithy-language-server` or `bin/smithy-language-server.bat`, that runs
smithy-language-server.

For example, on an ARM Mac:

```shell
curl -o download.zip -L "https://github.com/smithy-lang/smithy-language-server/releases/latest/download/smithy-language-server-darwin-aarch64.zip"
unzip download.zip -d smithy-language-server
smithy-language-server/bin/smithy-language-server --help
```

Run smithy-language-server by running the launch script with no arguments - this
will start the server, using std io to communicate with the client.

You can configure your editor to run the launch script directly, but most
editors will look for a `smithy-language-server` executable in `$PATH` by default.
To make use of this default, add a symlink to the launch script somewhere in
`$PATH`. For example, if `~/.local/bin` is in `$PATH`:

```shell
ln -s /path/to/installation/bin/smithy-language-server ~/.local/bin/smithy-language-server
```

And confirm `smithy-language-server` is in `$PATH`:

```shell
smithy-language-server --help
```

If you choose to do this differently, don't move the launch script outside the
installation. Also note that the installation's `bin/` directory contains other
scripts, so adding it to `$PATH` will also add those scripts to `$PATH`.

If your editor can't find the executable even though it is in `$PATH`, the editor
probably isn't using the same path as your shell. You can try running the editor
with the shell, or looking into how to configure the editor environment.

### Maven Artifacts

_Java 21+ is required._

smithy-language-server is published to [Maven Central][maven-central]. You can
use [Coursier][coursier] to run it without having to download it yourself:

```shell
cs launch --contrib smithy-language-server
```

Will run the latest version of smithy-language-server in Maven Central.

### Build from Source

_Java 21+ is required._

First clone this repo:

```shell
git clone https://github.com/smithy-lang/smithy-language-server.git
cd smithy-language-server
```

Build (also runs all tests):

```shell
./gradlew build
```

The built JAR can be run directly with `java`:

```shell
java -jar build/libs/smithy-language-server-<version>.jar
```

replacing `<version>` with the version in the [VERSION][version-file] file.

You can also build self-contained, platform-specific installations:

```shell
./gradlew runtime
build/image/smithy-language-server-<platform>/bin/smithy-language-server
```

An installation will be built for each platform, so just replace `<platform>`
with the platform of your local machine.

## Supported Project Layouts

smithy-language-server works with standalone `.smithy` files, and projects
with multiple source files and dependencies. Projects that configure sources
and dependencies in [`smithy-build.json`][smithy-build.json]
are supported out-of-the-box. For other kinds of projects, like Gradle projects,
you can write a [`.smithy-project.json`](#smithy-projectjson) file to tell
smithy-language-server about your project's sources and dependencies. Any Smithy
file that isn't in the sources of `smithy-build.json` or `.smithy-project.json`
will be treated as its own isolated "project".

### .smithy-project.json

`.smithy-project.json` is a file used specifically by smithy-language-server to
determine what files and dependencies are part of a project that is some external
build system, like Gradle. It has the following top-level properties:

| Property     | Type       | Description                                                                        |
|--------------|------------|------------------------------------------------------------------------------------|
| sources      | `[string]` | List of file or directory paths containing the source Smithy files in the project. |
| dependencies | `[object]` | List of the project's dependencies.                                                |

Each object in `dependencies` has the following properties:

| Property | Type     | Description                                                    |
|----------|----------|----------------------------------------------------------------|
| name     | `string` | (**required**) Name of the dependency.                         |
| path     | `string` | (**required**) Path to the JAR file containing the dependency. |

For example:

```json
{
    "sources": ["foo.smithy", "model/", "/absolute/path/"],
    "dependencies": [
        {
            "name": "Foo",
            "path": "path/to/foo.jar"
        },
        {
            "name": "Bar",
            "path": "/absolute/path/to/bar.jar"
        }
    ]
}
```

You can add `.smithy-project.json` to a project that also has `smithy-build.json`,
and both will be picked up by smithy-language-server. You might do this in a
project that uses `smithy-build.json` to configure projections, but defines
sources and dependencies elsewhere. If both `smithy-build.json` and `.smithy-project.json`
define sources and dependencies, they will be merged together and de-duplicated.

## Features

- Completions in Smithy files and `smithy-build.json`
- Hover information in Smithy files and `smithy-build.json`
- Diagnostics for Smithy validation events and issues in `smithy-build.json`
- Jump to definition
- Find references
- Rename references
- Formatting
- Automatic imports and import cleanup
- File structure (document symbols)
- Code folding
- Inlay hints for inline input/output

## Security

See [CONTRIBUTING](CONTRIBUTING.md#security-issue-notifications) for more information.

## License

This project is licensed under the Apache-2.0 License.

[lsp]: https://microsoft.github.io/language-server-protocol
[smithy]: https://smithy.io
[releases]: https://github.com/smithy-lang/smithy-language-server/releases
[smithy-vscode]: https://marketplace.visualstudio.com/items?itemName=smithy.smithy-vscode-extension
[smithy-build.json]: https://github.com/smithy-lang/smithy-language-server/releases
[maven-central]: https://central.sonatype.com/artifact/software.amazon.smithy/smithy-language-server
[coursier]: https://get-coursier.io/
[version-file]: https://github.com/smithy-lang/smithy-language-server/blob/main/VERSION

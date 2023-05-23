## Smithy Language Server
[![Build Status](https://github.com/awslabs/smithy-language-server/workflows/ci/badge.svg)](https://github.com/awslabs/smithy-language-server/actions/workflows/ci.yml)

A [Language Server Protocol](https://microsoft.github.io/language-server-protocol/)
implementation for the [Smithy IDL](https://awslabs.github.io/smithy/).


### Running the LSP

There are three ways to launch the LSP, and which you choose depends on your use case.

In all cases, the communication protocol is JSON-RPC, the transport channels can are:

#### Stdio

Run `./gradlew run --args="0"`

The LSP will use stdio (stdin, stdout) to communicate.

#### Sockets

Run `./gradlew run --args="12423"`

The LSP will try to connect to the given port using a TCP socket - if it can't, it will fail.

This is used by the VSCode extension to establish a connection between it and the LSP (which is launched
as a local process)

#### WebSockets

Run `./gradlew run --args="3000 --ws"`

The LSP will start a WebSocket server, which listens on given port.

This can be used to connect to a remote server running the LSP (more specifically from, but not limited to, the browser).  

## Security

See [CONTRIBUTING](CONTRIBUTING.md#security-issue-notifications) for more information.

## License

This project is licensed under the Apache-2.0 License.


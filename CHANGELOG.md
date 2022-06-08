# Smithy Language Server Changelog

## 0.1.0 (2022-06-08)

### Features
* Initial Language Server implementation handling server initialization and shutdown. ([#1](https://github.com/awslabs/smithy-language-server/pull/1))
* Added auto-completions, including context aware trait completions. ([#1](https://github.com/awslabs/smithy-language-server/pull/1), [#45](https://github.com/awslabs/smithy-language-server/pull/45))
* Added jump to definition for shapes defined within a workspace. ([#1](https://github.com/awslabs/smithy-language-server/pull/1), [#35](https://github.com/awslabs/smithy-language-server/pull/35))
* Added returning model validation events as code diagnostics. ([#1](https://github.com/awslabs/smithy-language-server/pull/1))
* Added jump to definition for shapes defined in dependency jar. ([#7](https://github.com/awslabs/smithy-language-server/pull/7))
* Maven configuration for smithy-build.json to make model dependencies available to Language Server. (([#32](https://github.com/awslabs/smithy-language-server/pull/32), [#34](https://github.com/awslabs/smithy-language-server/pull/34)
* Added Language Server extension to allow language client to run selector expression on a model. ([#36](https://github.com/awslabs/smithy-language-server/pull/36) 

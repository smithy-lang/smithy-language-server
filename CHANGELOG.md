# Smithy Language Server Changelog

## 0.2.4 (2024-11-08)

### Features
* Upgraded Smithy version to 1.40.0 ([#128](https://github.com/awslabs/smithy-language-server/pull/128))
* Added setting to enable LspLog ([#125](https://github.com/awslabs/smithy-language-server/pull/125))
* Added formatting with smithytranslate-formatter ([#117](https://github.com/awslabs/smithy-language-server/pull/117))
* Added Smithy CLI maven resolution ([#113](https://github.com/awslabs/smithy-language-server/pull/113))
* Added basic textDocument/documentSymbol ([#99](https://github.com/awslabs/smithy-language-server/pull/99))

### Bug fixes
* Fixed matching inline inputs and outputs with operations ([#111](https://github.com/awslabs/smithy-language-server/pull/111))
* Fixed loading of workspaces ([#101](https://github.com/awslabs/smithy-language-server/pull/101))
* Fixed location of elided members ([#98](https://github.com/awslabs/smithy-language-server/pull/98))

## 0.2.3 (2023-03-15)

### Features
* Added formatter to apply style guide to model files ([#89](https://github.com/awslabs/smithy-language-server/pull/89))
* Added diagnostic and code action to define or update to IDL 2 ([#86](https://github.com/awslabs/smithy-language-server/pull/86))

## 0.2.2 (2022-12-27)

### Bug fixes
* Fix bug where validators from external jars were not being used ([#82](https://github.com/awslabs/smithy-language-server/pull/82))

## 0.2.1 (2022-09-29)

### Features
* Display error message in hover content when hovering on a shape with unknown traits ([#74](https://github.com/awslabs/smithy-language-server/pull/74))
* Allow definition and hover to continue working in a model with unknown traits ([#74](https://github.com/awslabs/smithy-language-server/pull/74))

### Bug fixes
* Fix crashes when trying to hover in a model with unknown traits ([#74](https://github.com/awslabs/smithy-language-server/pull/74))
* Fix crashes caused by using an apply statement on mixed in members from another namespace ([#70](https://github.com/awslabs/smithy-language-server/pull/70))

## 0.2.0 (2022-08-29)

### Features
* Added Smithy IDL 2 support ([#61](https://github.com/awslabs/smithy-language-server/pull/61))
* Added support for hovering over shapes to show their definitions ([#63](https://github.com/awslabs/smithy-language-server/pull/63))

## 0.1.0 (2022-06-08)

### Features
* Initial Language Server implementation handling server initialization and shutdown. ([#1](https://github.com/awslabs/smithy-language-server/pull/1))
* Added auto-completions, including context aware trait completions. ([#1](https://github.com/awslabs/smithy-language-server/pull/1), [#45](https://github.com/awslabs/smithy-language-server/pull/45))
* Added jump to definition for shapes defined within a workspace. ([#1](https://github.com/awslabs/smithy-language-server/pull/1), [#35](https://github.com/awslabs/smithy-language-server/pull/35))
* Added returning model validation events as code diagnostics. ([#1](https://github.com/awslabs/smithy-language-server/pull/1))
* Added jump to definition for shapes defined in dependency jar. ([#7](https://github.com/awslabs/smithy-language-server/pull/7))
* Maven configuration for smithy-build.json to make model dependencies available to Language Server. (([#32](https://github.com/awslabs/smithy-language-server/pull/32), [#34](https://github.com/awslabs/smithy-language-server/pull/34)
* Added Language Server extension to allow language client to run selector expression on a model. ([#36](https://github.com/awslabs/smithy-language-server/pull/36) 

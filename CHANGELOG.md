# Smithy Language Server Changelog

## 0.7.0 (2025-04-15)

### Features
* Added standalone runtime images to GitHub release, to run the language server without a local Java installation. ([#159](https://github.com/smithy-lang/smithy-language-server/pull/159))
* Improved how the language server is launched from the CLI. ([#218](https://github.com/smithy-lang/smithy-language-server/pull/218))
* Added textDocument/rename support. ([#213](https://github.com/smithy-lang/smithy-language-server/pull/213))
* Added textDocument/references support. ([#213](https://github.com/smithy-lang/smithy-language-server/pull/213))
* Made textDocument/documentSymbol return hierarchical symbols. ([#206](https://github.com/smithy-lang/smithy-language-server/pull/206))

### Bug fixes
* Fixed possible crash on initialization. ([#216](https://github.com/smithy-lang/smithy-language-server/pull/216))
* Removed extraneous validation events from hover content. ([#214](https://github.com/smithy-lang/smithy-language-server/pull/214))

## 0.6.0 (2025-03-10)

### Features
* Improved completions, definition, and hover for everything in the IDL. ([#166](https://github.com/smithy-lang/smithy-language-server/pull/166))
* Diagnostics for smithy-build.json. ([#188](https://github.com/smithy-lang/smithy-language-server/pull/188))
* Completions for smithy-build.json. ([#193](https://github.com/smithy-lang/smithy-language-server/pull/193))
* Hover for smithy-build.json. ([#202](https://github.com/smithy-lang/smithy-language-server/pull/202))
* Folding range for traits and shape blocks. ([#190](https://github.com/smithy-lang/smithy-language-server/pull/190))
* Inlay hints of the name of inline operation input/output. ([#200](https://github.com/smithy-lang/smithy-language-server/pull/200))

### Bug fixes
* Fixed crash when calling setTrace or cancelProgress. ([#183](https://github.com/smithy-lang/smithy-language-server/pull/183))
* Fixed potential conflicting trait definition when rebuilding. ([#196](https://github.com/smithy-lang/smithy-language-server/pull/196))

## 0.5.0 (2024-11-06)

### Features
* Added support for projects nested in subdirectories of a workspace. The server can now load multiple projects within the same workspace. ([#167](https://github.com/smithy-lang/smithy-language-server/pull/167))
* Improved location of diagnostics. Diagnostics now appear only on the token, rather than including a bunch of whitespace. ([#179](https://github.com/smithy-lang/smithy-language-server/pull/179))

### Bug fixes
* Fixed potential deadlock in `didChangeWorkspaceFolders`. ([#167](https://github.com/smithy-lang/smithy-language-server/pull/167))

## 0.4.1 (2024-09-09)

### Features
* Added support for multiple workspaces. ([#160](https://github.com/smithy-lang/smithy-language-server/pull/160))

### Bug fixes
* Fixed file patterns for `didChangeWatchedFiles` on Windows. ([#160](https://github.com/smithy-lang/smithy-language-server/pull/160))

## 0.4.0 (2024-07-30)

### Breaking
* Upgraded JDK version to 21 ([#157](https://github.com/smithy-lang/smithy-language-server/pull/157))

### Features
* Made various performance improvements ([#146](https://github.com/smithy-lang/smithy-language-server/pull/146))
* Added support for using build-system agnostic .smithy-project.json to tell the server where project files and locally dependencies are ([#146](https://github.com/smithy-lang/smithy-language-server/pull/146))
* Added configuration option for minimum severity of validation events ([#146](https://github.com/smithy-lang/smithy-language-server/pull/146))
* Switched to smithy-syntax formatter ([#146](https://github.com/smithy-lang/smithy-language-server/pull/146))
* Added progress reporting on load ([#146](https://github.com/smithy-lang/smithy-language-server/pull/146))

### Removed/Replaced
* Removed generation of smithy.lsp.log file in favor of smithyLsp.trace.server and sending client logMessage notifications ([#146](https://github.com/smithy-lang/smithy-language-server/pull/146))
* Removed loading of every .smithy file found in all subdirectories of root path. Instead, server loads single files as separate models, or any files specified in smithy-build.json as a project ([#146](https://github.com/smithy-lang/smithy-language-server/pull/146))

### Bug fixes
* Fixed erroneously loading files in build directories, which could cause conflicting shape definitions ([#146](https://github.com/smithy-lang/smithy-language-server/pull/146))

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

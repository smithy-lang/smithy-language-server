$version: "2.0"

namespace smithy.lang.server

structure SmithyProjectJson {
    sources: Strings
    imports: Strings
    outputDirectory: String
    dependencies: ProjectDependencies
}

list ProjectDependencies {
    member: ProjectDependency
}

structure ProjectDependency {
    name: String

    @required
    path: String
}

structure SmithyBuildJson {
    /// Defines the version of smithy-build. Set to 1.0.
    @required
    version: SmithyBuildVersion

    /// The location where projections are written. Each projection will create a
    /// subdirectory named after the projection, and the artifacts from the projection,
    /// including a model.json file, will be placed in the directory.
    outputDirectory: String

    /// Provides a list of relative files or directories that contain the models
    /// that are considered the source models of the build. When a directory is
    /// encountered, all files in the entire directory tree are added as sources.
    /// Sources are relative to the configuration file.
    sources: Strings

    /// Provides a list of model files and directories to load when validating and
    /// building the model. Imports are a local dependency: they are not considered
    /// part of model package being built, but are required to build the model package.
    /// Models added through imports are not present in the output of the built-in
    /// sources plugin.
    /// When a directory is encountered, all files in the entire directory tree are
    /// imported. Imports defined at the top-level are used in every projection.
    /// Imports are relative to the configuration file.
    imports: Strings

    /// A map of projection names to projection configurations.
    @externalDocumentation(
        "Projections Reference": "https://smithy.io/2.0/guides/smithy-build-json.html#projections"
    )
    projections: Projections

    /// Defines the plugins to apply to the model when building every projection.
    /// Plugins are a mapping of plugin IDs to plugin-specific configuration objects.
    @externalDocumentation(
        "Plugins Reference": "https://smithy.io/2.0/guides/smithy-build-json.html#plugins"
    )
    plugins: Plugins

    /// If a plugin can't be found, Smithy will by default fail the build. This setting
    /// can be set to true to allow the build to progress even if a plugin can't be
    /// found on the classpath.
    ignoreMissingPlugins: Boolean

    /// Defines Java Maven dependencies needed to build the model. Dependencies are
    /// used to bring in model imports, build plugins, validators, transforms, and
    /// other extensions.
    @externalDocumentation(
        "Maven Reference": "https://smithy.io/2.0/guides/smithy-build-json.html#maven-configuration"
    )
    maven: Maven
}

@default("1")
string SmithyBuildVersion

map Projections {
    key: String
    value: Projection
}

structure Projection {
    /// Defines the projection as a placeholder that other projections apply. Smithy
    /// will not build artifacts for abstract projections. Abstract projections must
    /// not define imports or plugins.
    abstract: Boolean

    /// Provides a list of relative imports to include when building this specific
    /// projection (in addition to any imports defined at the top-level). When a
    /// directory is encountered, all files in the directory tree are imported.
    /// Note: imports are relative to the configuration file.
    imports: Strings

    /// Defines the transformations to apply to the projection. Transformations are
    /// used to remove shapes, remove traits, modify trait contents, and any other
    /// kind of transformation necessary for the projection. Transforms are applied
    /// in the order defined.
    @externalDocumentation(
        "Transforms Reference": "https://smithy.io/2.0/guides/smithy-build-json.html#transforms"
    )
    transforms: Transforms

    /// Defines the plugins to apply to the model when building this projection.
    /// plugins is a mapping of a plugin IDs to plugin-specific configuration objects.
    /// smithy-build will attempt to resolve plugin names using Java SPI to locate
    /// an instance of software.amazon.smithy.build.SmithyBuildPlugin that returns a
    /// matching name when calling getName. smithy-build will emit a warning when a
    /// plugin cannot be resolved.
    @externalDocumentation(
        "Plugins Reference": "https://smithy.io/2.0/guides/smithy-build-json.html#plugins"
    )
    plugins: Plugins
}

map Plugins {
    key: String
    value: Document
}

list Transforms {
    member: Transform
}

structure Transform {
    /// The required name of the transform.
    @required
    name: String

    /// A structure that contains configuration key-value pairs.
    args: TransformArgs
}

structure TransformArgs {
}

structure Maven {
    /// A list of Maven dependency coordinates in the form of groupId:artifactId:version.
    /// The Smithy CLI will search each registered Maven repository for the dependency.
    dependencies: Strings

    /// A list of Maven repositories to search for dependencies. If no repositories
    /// are defined and the SMITHY_MAVEN_REPOS environment variable is not defined,
    /// then this value defaults to Maven Central.
    repositories: MavenRepositories
}

list MavenRepositories {
    member: MavenRepository
}

structure MavenRepository {
    /// The URL of the repository (for example, https://repo.maven.apache.org/maven2).
    @required
    url: String

    /// HTTP basic or digest credentials to use with the repository. Credentials are
    /// provided in the form of "username:password".
    ///
    /// **WARNING** Credentials SHOULD NOT be defined statically in a smithy-build.json
    /// file. Instead, use environment variables to keep credentials out of source control.
    httpCredentials: String

    /// The URL of the proxy to configure for this repository (for example,
    /// http://proxy.maven.apache.org:8080).
    proxyHost: String

    /// HTTP credentials to use with the proxy for the repository. Credentials are
    /// provided in the form of "username:password".
    ///
    /// **WARNING** Credentials SHOULD NOT be defined statically in a smithy-build.json
    /// file. Instead, use environment variables to keep credentials out of source control.
    proxyCredentials: String
}

list Strings {
    member: String
}

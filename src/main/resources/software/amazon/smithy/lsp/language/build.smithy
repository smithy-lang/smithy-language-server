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
    @required
    version: SmithyBuildVersion

    outputDirectory: String
    sources: Strings
    imports: Strings
    projections: Projections
    plugins: Plugins
    ignoreMissingPlugins: Boolean
    maven: Maven
}

@default("1")
string SmithyBuildVersion

map Projections {
    key: String
    value: Projection
}

structure Projection {
    abstract: Boolean
    imports: Strings
    transforms: Transforms
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
    @required
    name: String

    args: TransformArgs
}

structure TransformArgs {
}

structure Maven {
    dependencies: Strings
    repositories: MavenRepositories
}

list MavenRepositories {
    member: MavenRepository
}

structure MavenRepository {
    @required
    url: String

    httpCredentials: String
    proxyHost: String
    proxyCredentials: String
}

list Strings {
    member: String
}

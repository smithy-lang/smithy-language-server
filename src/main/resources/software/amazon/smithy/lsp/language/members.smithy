$version: "2.0"

namespace smithy.lang.server

structure ShapeMemberTargets {
    service: ServiceShape
    operation: OperationShape
    resource: ResourceShape
    list: ListShape
    map: MapShape
}

structure ServiceShape {
    version: String
    operations: Operations
    resources: Resources
    errors: Errors
    rename: Rename
}

list Operations {
    member: AnyOperation
}

list Resources {
    member: AnyResource
}

list Errors {
    member: AnyError
}

map Rename {
    key: AnyShape
    value: String
}

structure OperationShape {
    input: AnyMemberTarget
    output: AnyMemberTarget
    errors: Errors
}

structure ResourceShape {
    identifiers: Identifiers
    properties: Properties
    create: AnyOperation
    put: AnyOperation
    read: AnyOperation
    update: AnyOperation
    delete: AnyOperation
    list: AnyOperation
    operations: Operations
    collectionOperations: Operations
    resources: Resources
}

map Identifiers {
    key: String
    value: AnyString
}

map Properties {
    key: String
    value: AnyMemberTarget
}

structure ListShape {
    member: AnyMemberTarget
}

structure MapShape {
    key: AnyString
    value: AnyMemberTarget
}

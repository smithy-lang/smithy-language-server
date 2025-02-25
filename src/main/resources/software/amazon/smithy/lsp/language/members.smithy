$version: "2.0"

namespace smithy.lang.server

union ShapeMemberTargets {
    /// A service is the entry point of an API that aggregates resources and operations together.
    @externalDocumentation("Service Reference": "https://smithy.io/2.0/spec/service-types.html#service")
    service: ServiceShape

    /// The operation type represents the input, output, and possible errors of an API operation.
    @externalDocumentation("Operation Reference": "https://smithy.io/2.0/spec/service-types.html#operation")
    operation: OperationShape

    /// Smithy defines a resource as an entity with an identity that has a set of operations.
    @externalDocumentation("Resource Reference": "https://smithy.io/2.0/spec/service-types.html#resource")
    resource: ResourceShape

    /// The list type represents an ordered homogeneous collection of values.
    @externalDocumentation("List Reference": "https://smithy.io/2.0/spec/aggregate-types.html#list")
    list: ListShape

    /// The map type represents a map data structure that maps string keys to homogeneous values.
    @externalDocumentation("Map Reference": "https://smithy.io/2.0/spec/aggregate-types.html#map")
    map: MapShape

    /// The structure type represents a fixed set of named, unordered, heterogeneous values.
    @externalDocumentation("Structure Reference": "https://smithy.io/2.0/spec/aggregate-types.html#structure")
    structure: Unit

    /// The union type represents a tagged union data structure that can take on several different, but fixed, types.
    @externalDocumentation("Union Reference": "https://smithy.io/2.0/spec/aggregate-types.html#union")
    union: Unit

    /// A blob is uninterpreted binary data.
    blob: Unit

    /// A boolean is a Boolean value type.
    boolean: Unit

    /// A string is a UTF-8 encoded string.
    string: Unit

    /// A byte is an 8-bit signed integer ranging from -128 to 127 (inclusive).
    byte: Unit

    /// A short is a 16-bit signed integer ranging from -32,768 to 32,767 (inclusive).
    short: Unit

    /// An integer is a 32-bit signed integer ranging from -2^31 to (2^31)-1 (inclusive).
    integer: Unit

    /// A long is a 64-bit signed integer ranging from -2^63 to (2^63)-1 (inclusive).
    long: Unit

    /// A float is a single precision IEEE-754 floating point number.
    float: Unit

    /// A double is a double precision IEEE-754 floating point number.
    double: Unit

    /// A bigInteger is an arbitrarily large signed integer.
    bigInteger: Unit

    /// A bigDecimal is an arbitrary precision signed decimal number.
    bigDecimal: Unit

    /// A timestamp represents an instant in time in the proleptic Gregorian calendar,
    /// independent of local times or timezones. Timestamps support an allowable date
    /// range between midnight January 1, 0001 CE to 23:59:59.999 on December 31, 9999 CE,
    /// with a temporal resolution of 1 millisecond.
    @externalDocumentation("Timestamp Reference": "https://smithy.io/2.0/spec/simple-types.html#timestamp")
    timestamp: Unit

    /// A document represents protocol-agnostic open content that functions as a kind of
    /// "any" type. Document types are represented by a JSON-like data model and can
    /// contain UTF-8 strings, arbitrary precision numbers, booleans, nulls, a list of
    /// these values, and a map of UTF-8 strings to these values.
    @externalDocumentation("Document Reference": "https://smithy.io/2.0/spec/simple-types.html#document")
    document: Unit

    /// The enum shape is used to represent a fixed set of one or more string values.
    @externalDocumentation("Enum Reference": "https://smithy.io/2.0/spec/simple-types.html#enum")
    enum: Unit

    /// An intEnum is used to represent an enumerated set of one or more integer values.
    @externalDocumentation("IntEnum Reference": "https://smithy.io/2.0/spec/simple-types.html#intenum")
    intEnum: Unit
}

@externalDocumentation("Service Reference": "https://smithy.io/2.0/spec/service-types.html#service")
structure ServiceShape {
    /// Defines the optional version of the service. The version can be provided in any
    /// format (e.g., 2017-02-11, 2.0, etc).
    version: String

    /// Binds a set of operation shapes to the service. Each element in the given list
    /// MUST be a valid shape ID that targets an operation shape.
    @externalDocumentation(
        "Operations Reference": "https://smithy.io/2.0/spec/service-types.html#service-operations"
    )
    operations: Operations

    /// Binds a set of resource shapes to the service. Each element in the given list MUST
    /// be a valid shape ID that targets a resource shape.
    @externalDocumentation(
        "Resources Reference": "https://smithy.io/2.0/spec/service-types.html#service-resources"
    )
    resources: Resources

    /// Defines a list of common errors that every operation bound within the closure of
    /// the service can return. Each provided shape ID MUST target a structure shape that
    /// is marked with the error trait.
    errors: Errors

    /// Disambiguates shape name conflicts in the service closure. Map keys are shape IDs
    /// contained in the service, and map values are the disambiguated shape names to use
    /// in the context of the service. Each given shape ID MUST reference a shape contained
    /// in the closure of the service. Each given map value MUST match the smithy:Identifier
    /// production used for shape IDs. Renaming a shape does not give the shape a new shape ID.
    /// - No renamed shape name can case-insensitively match any other renamed shape name
    ///   or the name of a non-renamed shape contained in the service.
    /// - Member shapes MAY NOT be renamed.
    /// - Resource and operation shapes MAY NOT be renamed. Renaming shapes is intended for
    ///   incidental naming conflicts, not for renaming the fundamental concepts of a service.
    /// - Shapes from other namespaces marked as private MAY be renamed.
    /// - A rename MUST use a name that is case-sensitively different from the original shape ID name.
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

@externalDocumentation("Operation Reference": "https://smithy.io/2.0/spec/service-types.html#operation")
structure OperationShape {
    /// The input of the operation defined using a shape ID that MUST target a structure.
    /// - Every operation SHOULD define a dedicated input shape marked with the
    ///   input trait. Creating a dedicated input shape ensures that input members
    ///   can be added in the future if needed.
    /// - Input defaults to smithy.api#Unit if no input is defined, indicating that
    ///   the operation has no meaningful input.
    input: AnyMemberTarget

    /// The output of the operation defined using a shape ID that MUST target a structure.
    /// - Every operation SHOULD define a dedicated output shape marked with the
    ///   output trait. Creating a dedicated output shape ensures that output members
    ///   can be added in the future if needed.
    /// - Output defaults to smithy.api#Unit if no output is defined, indicating that
    /// the operation has no meaningful output.
    output: AnyMemberTarget

    /// The errors that an operation can return. Each string in the list is a shape ID that
    /// MUST target a structure shape marked with the error trait.
    errors: Errors
}

@externalDocumentation("Resource Reference": "https://smithy.io/2.0/spec/service-types.html#resource")
structure ResourceShape {
    /// Defines a map of identifier string names to Shape IDs used to identify the resource.
    /// Each shape ID MUST target a string shape.
    @externalDocumentation(
        "Identifiers Reference": "https://smithy.io/2.0/spec/service-types.html#resource-identifiers"
    )
    identifiers: Identifiers

    /// Defines a map of property string names to Shape IDs that enumerate the properties
    /// of the resource.
    @externalDocumentation(
        "Properties Reference": "https://smithy.io/2.0/spec/service-types.html#resource-properties"
    )
    properties: Properties

    /// Defines the lifecycle operation used to create a resource using one or more
    /// identifiers created by the service. The value MUST be a valid Shape ID that targets an operation shape.
    @externalDocumentation(
        "Create Reference": "https://smithy.io/2.0/spec/service-types.html#create-lifecycle"
    )
    create: AnyOperation

    /// Defines an idempotent lifecycle operation used to create a resource using identifiers
    /// provided by the client. The value MUST be a valid Shape ID that targets an operation shape.
    @externalDocumentation(
        "Put Reference": "https://smithy.io/2.0/spec/service-types.html#put-lifecycle"
    )
    put: AnyOperation

    /// Defines the lifecycle operation used to retrieve the resource. The value MUST be
    /// a valid Shape ID that targets an operation shape.
    @externalDocumentation(
        "Read Reference": "https://smithy.io/2.0/spec/service-types.html#read-lifecycle"
    )
    read: AnyOperation

    /// Defines the lifecycle operation used to update the resource. The value MUST be a
    /// valid Shape ID that targets an operation shape.
    @externalDocumentation(
        "Update Reference": "https://smithy.io/2.0/spec/service-types.html#update-lifecycle"
    )
    update: AnyOperation

    /// Defines the lifecycle operation used to delete the resource. The value MUST be a
    /// valid Shape ID that targets an operation shape.
    @externalDocumentation(
        "Delete Reference": "https://smithy.io/2.0/spec/service-types.html#delete-lifecycle"
    )
    delete: AnyOperation

    /// Defines the lifecycle operation used to list resources of this type. The value
    /// MUST be a valid Shape ID that targets an operation shape.
    @externalDocumentation(
        "List Reference": "https://smithy.io/2.0/spec/service-types.html#list-lifecycle"
    )
    list: AnyOperation

    /// Binds a list of non-lifecycle instance operations to the resource. Each value in
    /// the list MUST be a valid Shape ID that targets an operation shape.
    operations: Operations

    /// Binds a list of non-lifecycle collection operations to the resource. Each value in
    /// the list MUST be a valid Shape ID that targets an operation shape.
    collectionOperations: Operations

    /// Binds a list of resources to this resource as a child resource, forming a containment
    /// relationship. Each value in the list MUST be a valid Shape ID that targets a resource.
    /// The resources MUST NOT have a cyclical containment hierarchy, and a resource can not
    /// be bound more than once in the entire closure of a resource or service.
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

// Note: No builtin docs for list/map members, because they could clobber user-defined docs.
//  We could add some logic to merge them, but I don't think it is worth it.
structure ListShape {
    member: AnyMemberTarget
}

structure MapShape {
    key: AnyString
    value: AnyMemberTarget
}

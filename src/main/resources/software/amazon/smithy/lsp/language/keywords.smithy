$version: "2.0"

namespace smithy.lang.server

union NonShapeKeywords {
    /// Metadata is a schema-less extensibility mechanism used to associate metadata to
    /// an entire model.
    @externalDocumentation(
        "Metadata Reference": "https://smithy.io/2.0/spec/model.html#model-metadata"
    )
    metadata: Unit

    /// A namespace is a mechanism for logically grouping shapes in a way that makes them
    /// reusable alongside other models without naming conflicts.
    @externalDocumentation(
        "Namespace Statement Reference": "https://smithy.io/2.0/spec/idl.html#namespaces"
        "Shape ID Reference": "https://smithy.io/2.0/spec/model.html#shape-id"
    )
    namespace: Unit

    /// The use section of the IDL is used to import shapes into the current namespace so
    /// that they can be referred to using a relative shape ID.
    @externalDocumentation(
        "Use Statement Reference": "https://smithy.io/2.0/spec/idl.html#referring-to-shapes"
    )
    use: Unit

    /// Applies a trait to a shape outside of the shape's definition
    @externalDocumentation(
        "Apply Statement Reference": "https://smithy.io/2.0/spec/idl.html#apply-statement"
        "Applying Traits Reference": "https://smithy.io/2.0/spec/model.html#applying-traits"
    )
    apply: Unit

    /// Allows referencing a resource's identifiers and properties in members to create
    /// resource bindings using target elision syntax.
    @externalDocumentation(
        "Identifier Bindings Reference": "https://smithy.io/2.0/spec/service-types.html#binding-identifiers-to-operations"
        "Property Bindings Reference": "https://smithy.io/2.0/spec/service-types.html#binding-members-to-properties"
        "Target Elision Syntax Reference": "https://smithy.io/2.0/spec/idl.html#idl-target-elision"
    )
    for: Unit

    /// Mixes in a list of mixins to a shape.
    @externalDocumentation(
        "Mixins Reference": "https://smithy.io/2.0/spec/idl.html#mixins"
    )
    with: Unit
}

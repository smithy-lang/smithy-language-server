$version: "2.0"

namespace smithy.lang.server

structure BuiltinMetadata {
    /// Suppressions are used to suppress specific validation events.
    @externalDocumentation("Suppressions Reference": "https://smithy.io/2.0/spec/model-validation.html#suppressions")
    suppressions: Suppressions

    /// An array of validator objects used to constrain the model.
    @externalDocumentation("Validators Reference": "https://smithy.io/2.0/spec/model-validation.html#validators")
    validators: Validators

    /// An array of severity override objects used to raise the severity of non-suppressed validation events.
    @externalDocumentation("Severity Overrides Reference": "https://smithy.io/2.0/spec/model-validation.html#severity-overrides")
    severityOverrides: SeverityOverrides
}

list Suppressions {
    member: Suppression
}

list Validators {
    member: Validator
}

list SeverityOverrides {
    member: SeverityOverride
}

@externalDocumentation("Suppressions Reference": "https://smithy.io/2.0/spec/model-validation.html#suppressions")
structure Suppression {
    /// The hierarchical validation event ID to suppress.
    id: String

    /// The validation event is only suppressed if it matches the supplied namespace.
    /// A value of * can be provided to match any namespace.
    /// * is useful for suppressing validation events that are not bound to any specific shape.
    namespace: AnyNamespace

    /// Provides an optional reason for the suppression.
    reason: String
}

@externalDocumentation("Validators Reference": "https://smithy.io/2.0/spec/model-validation.html#validators")
structure Validator {
    /// The name of the validator to apply. This name is used in implementations to find and configure
    /// the appropriate validator implementation. Validators only take effect if a Smithy processor
    /// implements the validator.
    name: ValidatorName

    /// Defines a custom identifier for the validator.
    /// Multiple instances of a single validator can be configured for a model. Providing
    /// an `id` allows suppressions to suppress a specific instance of a validator.
    /// If `id` is not specified, it will default to the name property of the validator definition.
    /// IDs that contain dots (.) are hierarchical. For example, the ID "Foo.Bar" contains
    /// the ID "Foo". Event ID hierarchies can be leveraged to group validation events and
    /// allow more granular suppressions.
    id: String

    /// Provides a custom message to use when emitting validation events. The special `{super}`
    /// string can be added to a custom message to inject the original error message of
    /// the validation event into the custom message.
    message: String

    /// Provides a custom severity level to use when a validation event occurs. If no severity
    /// is provided, then the default severity of the validator is used.
    ///
    /// **Note** The severity of user-defined validators cannot be set to `ERROR`.
    @externalDocumentation("Severity Reference": "https://smithy.io/2.0/spec/model-validation.html#severity-definition")
    severity: ValidatorSeverity

    /// Provides a list of the namespaces that are targeted by the validator. The validator
    /// will ignore any validation events encountered that are not specific to the given namespaces.
    namespaces: AnyNamespaces

    /// A valid selector that causes the validator to only validate shapes that match the
    /// selector. The validator will ignore any validation events encountered that do not
    /// satisfy the selector.
    @externalDocumentation("Selector Reference": "https://smithy.io/2.0/spec/selectors.html#selectors")
    selector: String

    /// Object that provides validator configuration. The available properties are defined
    /// by each validator. Validators MAY require that specific configuration properties are provided.
    configuration: ValidatorConfig
}

enum ValidatorSeverity {
    NOTE = "NOTE"
    WARNING = "WARNING"
    DANGER = "DANGER"
}

list AnyNamespaces {
    member: AnyNamespace
}

@externalDocumentation("Severity Overrides Reference": "https://smithy.io/2.0/spec/model-validation.html#severity-overrides")
structure SeverityOverride {
    /// The hierarchical validation event ID to elevate.
    id: String

    /// The validation event is only elevated if it matches the supplied namespace.
    /// A value of `*` can be provided to match any namespace.
    namespace: AnyNamespace

    /// Defines the severity to elevate matching events to. This value can only be set
    /// to `WARNING` or `DANGER`.
    @externalDocumentation("Severity Reference": "https://smithy.io/2.0/spec/model-validation.html#severity-definition")
    severity: SeverityOverrideSeverity
}

enum SeverityOverrideSeverity {
    WARNING = "WARNING"
    DANGER = "DANGER"
}

structure BuiltinValidators {
    /// Emits a validation event for each shape that matches the given selector.
    @externalDocumentation("EmitEachSelector Reference": "https://smithy.io/2.0/spec/model-validation.html#emiteachselector")
    EmitEachSelector: EmitEachSelectorConfig

    /// Emits a validation event if no shape in the model matches the given selector.
    @externalDocumentation("EmitNoneSelector Reference": "https://smithy.io/2.0/spec/model-validation.html#emitnoneselector")
    EmitNoneSelector: EmitNoneSelectorConfig

    UnreferencedShapes: UnreferencedShapesConfig
}

@externalDocumentation("EmitEachSelector Reference": "https://smithy.io/2.0/spec/model-validation.html#emiteachselector")
structure EmitEachSelectorConfig {
    /// A valid selector. A validation event is emitted for each shape in the model that matches the selector.
    @required
    selector: Selector

    /// An optional string that MUST be a valid shape ID that targets a trait definition.
    /// A validation event is only emitted for shapes that have this trait.
    bindToTrait: AnyTrait

    /// A custom template that is expanded for each matching shape and assigned as the message
    /// for the emitted validation event.
    messageTemplate: String
}

@externalDocumentation("EmitNoneSelector Reference": "https://smithy.io/2.0/spec/model-validation.html#emitnoneselector")
structure EmitNoneSelectorConfig {
    /// A valid selector. If no shape in the model is returned by the selector, then a validation event is emitted.
    @required
    selector: Selector
}

structure UnreferencedShapesConfig {
    selector: Selector = "service"
}
